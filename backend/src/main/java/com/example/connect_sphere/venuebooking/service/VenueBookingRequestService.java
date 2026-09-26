package com.example.connect_sphere.venuebooking.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.dto.EventVenueBookingDto;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.dto.VenueOptionDto;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EC03: an Event Coordinator requests a venue for an event they coordinate.
 *
 * <p>The request is only a request. It is created as {@code pending}, does
 * not reserve the venue, and does not count as a conflict for anyone else;
 * only a {@code confirmed} booking does (Week 4 'Booking Conflict
 * Detection'). Deciding between competing pending requests is Venue Staff's
 * job at approval (VS03/VS08), which must re-run the overlap check there.
 *
 * <p>Checks are ordered from "is this allowed at all" (assignment, event
 * status, existing request) to "is this venue acceptable" (capacity,
 * conflicts, justification), so the coordinator is told the most basic
 * problem first.
 */
@Service
public class VenueBookingRequestService {

    static final int MAX_TEXT_LENGTH = 2000;
    private static final List<VenueBookingStatus> ACTIVE =
            List.of(VenueBookingStatus.pending, VenueBookingStatus.confirmed);

    private final EventRepository events;
    private final VenueRepository venues;
    private final VenueBookingRecordRepository bookings;
    private final EventRequestRepository eventRequests;
    private final UserRepository users;
    private final VenueMapper venueMapper;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public VenueBookingRequestService(
            EventRepository events,
            VenueRepository venues,
            VenueBookingRecordRepository bookings,
            EventRequestRepository eventRequests,
            UserRepository users,
            VenueMapper venueMapper,
            ActivityService activityService,
            NotificationService notificationService) {
        this.events = events;
        this.venues = venues;
        this.bookings = bookings;
        this.eventRequests = eventRequests;
        this.users = users;
        this.venueMapper = venueMapper;
        this.activityService = activityService;
        this.notificationService = notificationService;
    }

    /**
     * Every catalogue venue judged against this event, best first: bookable
     * venues before ones needing a justification before blocked ones, and
     * within each group the tightest fit first, so a 40-person workshop is
     * not steered into the 500-seat hall.
     */
    @Transactional(readOnly = true)
    public List<VenueOptionDto> venueOptions(UUID coordinatorId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        Map<UUID, List<VenueOptionDto.ConflictDto>> conflictsByVenue = conflictsByVenue(event);
        return venues.findAll().stream()
                .map(venue -> option(event, venue, conflictsByVenue.getOrDefault(venue.getVenueId(), List.of())))
                .sorted(Comparator.comparingInt((VenueOptionDto o) -> verdictRank(o.verdict()))
                        .thenComparingInt(o -> Math.abs(o.spareCapacity())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventVenueBookingDto> bookingsForEvent(UUID coordinatorId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        return bookings.findForEvent(eventId).stream().map(this::toDto).toList();
    }

    @Transactional
    public EventVenueBookingDto submit(UUID coordinatorId, UUID eventId, SubmitVenueBookingRequest request) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        if (event.getStatus() != EventStatus.pending) {
            throw new VenueBookingStateException("Venue booking requests can only be made while an event is in "
                    + "Planning (this event is " + event.getStatus().name() + ").");
        }
        bookings.findFirstByEventIdAndStatusIn(eventId, ACTIVE).ifPresent(existing -> {
            throw new VenueBookingStateException("This event already has a " + existing.getStatus().name()
                    + " venue booking request. A new one can be made if that request is rejected or cancelled.");
        });
        if (request == null || request.venueId() == null) {
            throw new InvalidVenueBookingException("Select a venue before submitting the booking request.");
        }
        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> new VenueNotFoundException(request.venueId()));
        String notes = optionalText(request.bookingNotes(), "Booking notes");
        String justification = optionalText(request.suitabilityNote(), "The justification");

        VenueSuitability suitability = VenueSuitability.of(event, venue);
        if (!suitability.capacityOk()) {
            throw new InvalidVenueBookingException("Expected attendance (" + suitability.expectedAttendance()
                    + ") exceeds this venue's capacity (" + suitability.capacity() + ").");
        }
        List<VenueOptionDto.ConflictDto> conflicts =
                conflictsByVenue(event).getOrDefault(venue.getVenueId(), List.of());
        if (!conflicts.isEmpty()) {
            throw new InvalidVenueBookingException("This venue already has a confirmed booking that overlaps the "
                    + "event's time (" + conflicts.get(0).eventName() + "). Choose another venue.");
        }
        if (suitability.needsJustification() && justification == null) {
            throw new InvalidVenueBookingException("This venue does not offer "
                    + String.join(", ", suitability.missingAccessibility())
                    + ". Explain why it should still be considered, for Venue Staff to judge.");
        }

        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setVenueId(venue.getVenueId());
        booking.setEventId(eventId);
        booking.setStatus(VenueBookingStatus.pending);
        booking.setBookingNotes(notes);
        booking.setSuitabilityNote(suitability.needsJustification() ? justification : null);
        booking.setSubmittedBy(coordinatorId);
        booking.setSubmittedAt(OffsetDateTime.now());
        VenueBookingRecord saved = bookings.save(booking);

        // The event's originating request carries the timeline; an event
        // created outside the request flow simply has none to extend.
        eventRequests.findByEventId(eventId).ifPresent(origin -> activityService.record(
                new ActivityService.Entry(origin.getRequestId(), eventId, ActivityType.venue_booking_requested,
                        coordinatorId, "Requested " + venue.getVenueAddress() + (notes == null ? "" : ". " + notes),
                        null, null, VenueBookingStatus.pending.name())));

        List<UUID> venueStaff = users.findByRole(UserRole.vs).stream().map(User::getUserId).toList();
        if (!venueStaff.isEmpty()) {
            afterCommit(() -> notificationService.createVenueBookingRequestedNotifications(
                    venueStaff, saved.getBookingId(), eventId, event.getEventName()));
        }
        return toDto(saved);
    }

    /**
     * EC03: the coordinator withdraws their own pending request (e.g. they
     * picked the wrong venue), which frees the event to request another.
     * A confirmed booking cannot be withdrawn here: undoing a commitment Venue
     * Staff have made is a change request (EO03/EC-NEW3), not a cancellation.
     */
    @Transactional
    public EventVenueBookingDto cancel(UUID coordinatorId, UUID eventId, UUID bookingId, String reason) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        VenueBookingRecord booking = bookings.findById(bookingId)
                .filter(b -> eventId.equals(b.getEventId()))
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        if (booking.getStatus() != VenueBookingStatus.pending) {
            throw new VenueBookingStateException("Only a pending booking request can be cancelled (this one is "
                    + booking.getStatus().name() + ").");
        }
        String why = optionalText(reason, "The reason");
        booking.setStatus(VenueBookingStatus.cancelled);
        VenueBookingRecord saved = bookings.save(booking);

        String venueName = venues.findById(saved.getVenueId()).map(Venue::getVenueAddress).orElse("the venue");
        eventRequests.findByEventId(eventId).ifPresent(origin -> activityService.record(
                new ActivityService.Entry(origin.getRequestId(), eventId, ActivityType.venue_booking_cancelled,
                        coordinatorId, "Cancelled the request for " + venueName + (why == null ? "" : ". " + why),
                        null, VenueBookingStatus.pending.name(), VenueBookingStatus.cancelled.name())));

        List<UUID> venueStaff = users.findByRole(UserRole.vs).stream().map(User::getUserId).toList();
        if (!venueStaff.isEmpty()) {
            afterCommit(() -> notificationService.createVenueBookingCancelledNotifications(
                    venueStaff, saved.getBookingId(), eventId, event.getEventName(), why));
        }
        return toDto(saved);
    }

    private VenueOptionDto option(Event event, Venue venue, List<VenueOptionDto.ConflictDto> conflicts) {
        VenueSuitability suitability = VenueSuitability.of(event, venue);
        List<String> reasons = new ArrayList<>();
        if (!suitability.capacityOk()) {
            reasons.add("Capacity " + suitability.capacity() + " is below the expected attendance of "
                    + suitability.expectedAttendance() + ".");
        }
        for (VenueOptionDto.ConflictDto conflict : conflicts) {
            reasons.add("Already confirmed for " + conflict.eventName() + " at an overlapping time.");
        }
        if (suitability.needsJustification()) {
            reasons.add("Missing accessibility: " + String.join(", ", suitability.missingAccessibility()) + ".");
        }
        String verdict = !suitability.capacityOk() || !conflicts.isEmpty() ? "blocked"
                : suitability.needsJustification() ? "needs_justification"
                : "suitable";
        return new VenueOptionDto(venueMapper.toDto(venue), verdict, suitability.capacityOk(),
                suitability.capacity() - suitability.expectedAttendance(), suitability.missingAccessibility(),
                conflicts, reasons);
    }

    private Map<UUID, List<VenueOptionDto.ConflictDto>> conflictsByVenue(Event event) {
        return bookings.findConfirmedOverlapping(event.getEventId(), event.getStartDatetime(), event.getEndDatetime())
                .stream()
                .collect(Collectors.groupingBy(VenueBookingRecordRepository.Conflict::getVenueId,
                        Collectors.mapping(c -> new VenueOptionDto.ConflictDto(
                                c.getEventName(), c.getStartDatetime(), c.getEndDatetime()), Collectors.toList())));
    }

    private static int verdictRank(String verdict) {
        return switch (verdict) {
            case "suitable" -> 0;
            case "needs_justification" -> 1;
            default -> 2;
        };
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }

    /** Trimmed; blank becomes null; over the limit is refused. */
    private static String optionalText(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidVenueBookingException(what + " can be at most " + MAX_TEXT_LENGTH + " characters.");
        }
        return text;
    }

    private EventVenueBookingDto toDto(VenueBookingRecord b) {
        Venue venue = venues.findById(b.getVenueId()).orElse(null);
        String submittedBy = b.getSubmittedBy() == null ? null
                : users.findById(b.getSubmittedBy()).map(User::getUsername).orElse(null);
        return new EventVenueBookingDto(b.getBookingId(), b.getStatus(), b.getVenueId(),
                venue == null ? null : venue.getVenueAddress(), venue == null ? null : venue.getVenueCapacity(),
                b.getBookingNotes(), b.getSuitabilityNote(), b.getRejectReason(), b.getSubmittedAt(), submittedBy);
    }

    /** Same reason as EventRequestService.afterThisTransactionCommits: the
     * notification is written in its own transaction and must not run before
     * the booking it points at is committed. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
