package com.example.connect_sphere.venuebooking.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.safety.ReadinessService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.repository.VenueOperatingHourRepository;
import com.example.connect_sphere.venue.entity.VenueOperatingHour;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.dto.EventVenueBookingDto;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.dto.VenueOptionDto;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;

/**
 * EC03: an Event Coordinator requests a venue for an event they coordinate.
 *
 * <p>The request is only a request. It is created as {@code pending}, does
 * not reserve the venue, and does not count as a conflict for anyone else;
 * only a {@code approved} booking does (Week 4 'Booking Conflict
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
    private final com.example.connect_sphere.venueavailability.AvailabilityService availability;

    @Autowired(required = false)
    private com.example.connect_sphere.venuebooking.hold.VenueHoldService holdService;
    @Autowired(required = false)
    private com.example.connect_sphere.venuebooking.hold.VenueHoldRepository holds;
    static final int MAX_TEXT_LENGTH = 2000;
    private static final List<VenueBookingStatus> ACTIVE =
            List.of(VenueBookingStatus.pending, VenueBookingStatus.approved);

    private final EventRepository events;
    private final VenueRepository venues;
    private final VenueBookingRecordRepository bookings;
    private final EventRequestRepository eventRequests;
    private final UserRepository users;
    private final VenueMapper venueMapper;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final VenueOperationalIssueRepository operationalIssues;
    private final VenueOperatingHourRepository operatingHours;
    private final com.example.connect_sphere.event.service.EventLifecycleService lifecycle;

    @Autowired
    public VenueBookingRequestService(
            EventRepository events,
            VenueRepository venues,
            VenueBookingRecordRepository bookings,
            EventRequestRepository eventRequests,
            UserRepository users,
            VenueMapper venueMapper,
            ActivityService activityService,
            NotificationService notificationService,
            VenueOperationalIssueRepository operationalIssues,
            VenueOperatingHourRepository operatingHours,
            com.example.connect_sphere.venueavailability.AvailabilityService availability,
            com.example.connect_sphere.event.service.EventLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.availability = availability;
        this.events = events;
        this.venues = venues;
        this.bookings = bookings;
        this.eventRequests = eventRequests;
        this.users = users;
        this.venueMapper = venueMapper;
        this.activityService = activityService;
        this.notificationService = notificationService;
        this.operationalIssues = operationalIssues;
        this.operatingHours = operatingHours;
    }

    /**
     * Every catalogue venue judged against this event, best first: bookable
     * venues before ones needing a justification before blocked ones, and
     * within each group the tightest fit first, so a 40-person workshop is
     * not steered into the 500-seat hall.
     */
    @Transactional(readOnly = true)
    public List<VenueOptionDto> venueOptions(UUID coordinatorId, UUID eventId) {
        return venueOptions(coordinatorId, eventId, null, null);
    }

    @Transactional(readOnly = true)
    public List<VenueOptionDto> venueOptions(UUID coordinatorId, UUID eventId, java.math.BigDecimal setup, java.math.BigDecimal turnaround) {
        return venueOptions(coordinatorId, eventId, setup, turnaround, null, null);
    }

    @Transactional(readOnly = true)
    public List<VenueOptionDto> venueOptions(UUID coordinatorId, UUID eventId, java.math.BigDecimal setup,
            java.math.BigDecimal turnaround, OffsetDateTime requestedStart, OffsetDateTime requestedEnd) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        OffsetDateTime start = requestedStart == null ? event.getStartDatetime() : requestedStart;
        OffsetDateTime end = requestedEnd == null ? event.getEndDatetime() : requestedEnd;
        com.example.connect_sphere.venueavailability.EffectivePeriod.of(start, end,
                new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(0, 0));
        Map<UUID, List<VenueOptionDto.UnavailablePeriodDto>> unavailableByVenue = unavailableByVenue(start, end, setup, turnaround);
        Map<UUID, List<VenueOptionDto.UnavailablePeriodDto>> hoursByVenue = hoursByVenue(start, end, setup, turnaround);
        return venues.findAll().stream()
                .map(venue -> option(event, venue,
                        conflicts(event, venue, start, end, requestedDurations(venue, setup, turnaround)),
                        unavailableByVenue.getOrDefault(venue.getVenueId(), List.of()),
                        hoursByVenue.getOrDefault(venue.getVenueId(), List.of()), start, end, requestedDurations(venue, setup, turnaround)))
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

    /** SO-C2: a Safety Officer may inspect arrangements for any active event. */
    @Transactional(readOnly = true)
    public List<EventVenueBookingDto> bookingsForSafetyOfficer(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!ReadinessService.VISIBLE.contains(event.getStatus())) {
            throw new EventNotFoundException(eventId);
        }
        return bookings.findForEvent(eventId).stream().map(this::toDto).toList();
    }

    @Transactional
    public EventVenueBookingDto submit(UUID coordinatorId, UUID eventId, SubmitVenueBookingRequest request) {
        return submit(coordinatorId, eventId, request, null);
    }

    /** {@code addsToExisting}: whether the event already held an active booking
     * before this submission began (EC-NEW5 "additional venue added"); null to
     * work it out here. A multi-venue batch passes what was true before the
     * batch, so its own bookings are not "additional" to each other. */
    private EventVenueBookingDto submit(UUID coordinatorId, UUID eventId, SubmitVenueBookingRequest request,
            Boolean addsToExisting) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        boolean replacement = request != null && request.replacesBookingId() != null;
        if (!event.getStatus().isPlanningEditable() && !(replacement && event.getStatus() == EventStatus.confirmed)) {
            throw new VenueBookingStateException("Venue booking requests can only be made while an event is in "
                    + "Planning (this event is " + stageLabel(event.getStatus()) + ").");
        }
        List<VenueBookingRecord> active = bookings.findForEvent(eventId).stream().filter(b -> ACTIVE.contains(b.getStatus())).toList();
        if (replacement) {
            var original = bookings.findById(request.replacesBookingId())
                    .filter(b -> eventId.equals(b.getEventId()) && ACTIVE.contains(b.getStatus()))
                    .orElseThrow(() -> new VenueBookingStateException("Select an affected active booking to replace."));
            if (!availability.affected(original.getBookingId()))
                throw new VenueBookingStateException("This booking does not require alternative arrangements.");
            if (active.stream().anyMatch(b -> !b.getBookingId().equals(original.getBookingId())))
                throw new VenueBookingStateException("This event already has a replacement request. Resolve it first.");
        }
        if (request == null || request.venueId() == null) {
            throw new InvalidVenueBookingException("Select a venue before submitting the booking request.");
        }
        Venue venue = venues.findForUpdate(request.venueId())
                .orElseThrow(() -> new VenueNotFoundException(request.venueId()));
        if (!replacement && active.stream().anyMatch(b -> b.getVenueId().equals(venue.getVenueId()))) {
            throw new InvalidVenueBookingException("This venue has already been added to this event for the same time. Choose another venue.");
        }
        String notes = optionalText(request.bookingNotes(), "Booking notes");
        String justification = optionalText(request.suitabilityNote(), "The justification");
        OffsetDateTime start = request.startDatetime() == null ? event.getStartDatetime() : request.startDatetime();
        OffsetDateTime end = request.endDatetime() == null ? event.getEndDatetime() : request.endDatetime();
        if (start == null || end == null || !end.isAfter(start)) {
            throw new InvalidVenueBookingException("Each venue must have an end date/time later than its start date/time.");
        }
        var defaults = availability.settings(venue.getVenueId());
        var durations = new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(
                com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(request.setupDurationMinutes(), defaults.setupMinutes()),
                com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(request.turnaroundDurationMinutes(), defaults.turnaroundMinutes()));
        String timingNote = optionalText(request.timingNote(), "Timing reason");
        if (!durations.equals(defaults) && timingNote == null)
            throw new InvalidVenueBookingException("Explain why this booking needs different setup or turnaround durations.");

        VenueSuitability suitability = VenueSuitability.of(event, venue);
        if (!suitability.capacityOk()) {
            throw new InvalidVenueBookingException("Expected attendance (" + suitability.expectedAttendance()
                    + ") exceeds this venue's capacity (" + suitability.capacity() + ").");
        }
        var recorded = availability.unavailablePeriods(venue.getVenueId(), start, end, durations);
        if (!recorded.isEmpty())
            throw new InvalidVenueBookingException("This venue is unavailable during the event, setup or turnaround period ("
                    + describe(recorded.get(0)) + "). Choose another venue.");
        List<VenueOptionDto.ConflictDto> conflicts = conflicts(event, venue, start, end, durations);
        if (!suitability.facilitiesOk()) {
            throw new InvalidVenueBookingException("This venue does not offer the required facilities: "
                    + suitability.missingFacilityLabels() + ".");
        }
        List<VenueOptionDto.UnavailablePeriodDto> unavailable =
                unavailableByVenue(start, end, java.math.BigDecimal.valueOf(durations.setupMinutes()),
                        java.math.BigDecimal.valueOf(durations.turnaroundMinutes())).getOrDefault(venue.getVenueId(), List.of());
        List<VenueOptionDto.UnavailablePeriodDto> hoursUnavailable =
                hoursByVenue(start, end, java.math.BigDecimal.valueOf(durations.setupMinutes()),
                        java.math.BigDecimal.valueOf(durations.turnaroundMinutes())).getOrDefault(venue.getVenueId(), List.of());
        if (!hoursUnavailable.isEmpty()) {
            throw new InvalidVenueBookingException("Venue '" + venue.getVenueAddress() + "' is outside its operating hours: "
                    + hoursUnavailable.get(0).description() + ".");
        }
        if (!unavailable.isEmpty()) {
            throw new InvalidVenueBookingException("This venue is unavailable during the event, setup or turnaround period: "
                    + unavailable.get(0).description() + ".");
        }
        if (!conflicts.isEmpty()) {
            // ECL-C7: never another event's name, only that it is taken and when.
            throw new InvalidVenueBookingException("Venue '" + venue.getVenueAddress()
                    + "' is not free at this event's time: " + conflicts.get(0).sentence()
                    + ". Choose another venue.");
        }
        if (suitability.needsJustification() && justification == null) {
            throw new InvalidVenueBookingException("This venue does not offer "
                    + suitability.missingAccessibilityLabels()
                    + ". Explain why it should still be considered, for Venue Staff to judge.");
        }

        VenueBookingRecord booking = new VenueBookingRecord();
        // The venue lock above makes these the same defaults used by the availability checks.
        com.example.connect_sphere.venueavailability.EffectivePeriod.validate(durations);
        booking.setSetupDurationMinutes(durations.setupMinutes());
        booking.setTurnaroundDurationMinutes(durations.turnaroundMinutes());
        booking.setTimingNote(timingNote);
        booking.setBookingId(UUID.randomUUID());
        booking.setVenueId(venue.getVenueId());
        booking.setEventId(eventId);
        booking.setStartDatetime(start);
        booking.setEndDatetime(end);
        booking.setReplacesBookingId(request.replacesBookingId());
        booking.setStatus(VenueBookingStatus.pending);
        booking.setBookingNotes(notes);
        booking.setSuitabilityNote(suitability.needsJustification() ? justification : null);
        booking.setSubmittedBy(coordinatorId);
        booking.setSubmittedAt(OffsetDateTime.now());
        VenueBookingRecord saved = bookings.save(booking);

        // The event's originating request carries the timeline; an event
        // created outside the request flow simply has none to extend.
        // EC-NEW5: a replacement, an additional venue (Major) or a first request.
        ActivityType logged = request.replacesBookingId() != null ? ActivityType.venue_booking_replaced
                : (addsToExisting != null ? addsToExisting : hasActiveBooking(eventId, saved.getBookingId()))
                        ? ActivityType.venue_added : ActivityType.venue_booking_requested;
        eventRequests.findByEventId(eventId).ifPresent(origin -> activityService.record(
                new ActivityService.Entry(origin.getRequestId(), eventId, logged,
                        coordinatorId, requestedMessage(venue.getVenueAddress(), notes),
                        null, null, VenueBookingStatus.pending.name())));

        List<UUID> venueStaff = users.findByRole(UserRole.vs).stream().map(User::getUserId).toList();
        if (!venueStaff.isEmpty()) {
            afterCommit(() -> notificationService.createVenueBookingRequestedNotifications(
                    venueStaff, saved.getBookingId(), eventId, event.getEventName()));
        }
        return toDto(saved);
    }

    @Transactional
    public List<EventVenueBookingDto> submitBatch(UUID coordinatorId, UUID eventId, SubmitVenueBookingRequest.Batch batch) {
        // ECL-C7: who is asking is checked before anything about the input, so
        // a coordinator who is not assigned learns nothing about the event.
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        if (batch == null || batch.bookings() == null || batch.bookings().isEmpty()) {
            throw new InvalidVenueBookingException("Add at least one venue before submitting.");
        }
        List<SubmitVenueBookingRequest> requests = batch.bookings();
        for (var request : requests) {
            if (request == null || request.venueId() == null)
                throw new InvalidVenueBookingException("Select a venue for every booking.");
        }
        var lockedVenues = new java.util.HashMap<UUID, Venue>();
        requests.stream().map(SubmitVenueBookingRequest::venueId).distinct().sorted().forEach(id ->
                lockedVenues.put(id, venues.findForUpdate(id).orElseThrow(() -> new VenueNotFoundException(id))));
        for (int i = 0; i < requests.size(); i++) {
            var a = requests.get(i);
            var ap = requestedPeriod(event, lockedVenues.get(a.venueId()), a);
            for (int j = i + 1; j < requests.size(); j++) {
                var b = requests.get(j);
                if (!a.venueId().equals(b.venueId())) continue;
                var bp = requestedPeriod(event, lockedVenues.get(b.venueId()), b);
                if (ap.start().isBefore(bp.end()) && ap.end().isAfter(bp.start()))
                    throw new InvalidVenueBookingException("The same venue cannot be added for overlapping occupied times, including setup and turnaround.");
                throw new InvalidVenueBookingException("This venue has already been added to this event. Choose another venue.");
            }
        }
        boolean hadActiveBooking = hasActiveBooking(eventId, null);
        return requests.stream().map(request -> submit(coordinatorId, eventId, request, hadActiveBooking)).toList();
    }

    /** Another booking of this event that is pending or approved. */
    private boolean hasActiveBooking(UUID eventId, UUID except) {
        return bookings.findForEvent(eventId).stream()
                .filter(b -> except == null || !except.equals(b.getBookingId()))
                .anyMatch(b -> b.getStatus() == VenueBookingStatus.pending || b.getStatus() == VenueBookingStatus.approved);
    }

    private static com.example.connect_sphere.venueavailability.EffectivePeriod requestedPeriod(
            Event event, Venue venue, SubmitVenueBookingRequest request) {
        return com.example.connect_sphere.venueavailability.EffectivePeriod.of(
                request.startDatetime() == null ? event.getStartDatetime() : request.startDatetime(),
                request.endDatetime() == null ? event.getEndDatetime() : request.endDatetime(),
                requestedDurations(venue, request.setupDurationMinutes(), request.turnaroundDurationMinutes()));
    }

    /**
     * VS25: the assigned coordinator cancels an active booking. Status is the
     * reservation: retaining the cancelled row releases its entire occupied period.
     */
    @Transactional
    public EventVenueBookingDto cancel(UUID coordinatorId, UUID eventId, UUID bookingId, String reason) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        VenueBookingRecord booking = bookings.findById(bookingId)
                .filter(b -> eventId.equals(b.getEventId()))
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        venues.findForUpdate(booking.getVenueId()).orElseThrow(() -> new VenueNotFoundException(booking.getVenueId()));
        if (!ACTIVE.contains(booking.getStatus())) {
            throw new VenueBookingStateException("Only a pending or approved booking can be cancelled (this one is "
                    + booking.getStatus().name() + ").");
        }
        if (event.getStatus() == EventStatus.completed || event.getStatus() == EventStatus.cancelled)
            throw new VenueBookingStateException("Bookings for completed or cancelled events cannot be changed.");
        if (bookings.existsByReplacesBookingIdAndStatus(bookingId, VenueBookingStatus.pending))
            throw new VenueBookingStateException("Resolve the pending replacement request before cancelling the original booking.");
        String why = optionalText(reason, "The reason");
        VenueBookingStatus previous = booking.getStatus();
        if (holdService != null) holdService.endForBooking(bookingId, coordinatorId);
        booking.setStatus(VenueBookingStatus.cancelled);
        VenueBookingRecord saved = bookings.save(booking);
        if (previous == VenueBookingStatus.approved)
            lifecycle.returnToPlanning(eventId, coordinatorId, "An approved venue booking was cancelled."
                    + (why == null ? "" : " " + why));

        String venueName = venues.findById(saved.getVenueId()).map(Venue::getVenueAddress).orElse("the venue");
        eventRequests.findByEventId(eventId).ifPresent(origin -> activityService.record(
                new ActivityService.Entry(origin.getRequestId(), eventId, ActivityType.venue_booking_cancelled,
                        coordinatorId, "Cancelled the request for " + venueName + (why == null ? "" : ". " + why),
                        null, previous.name(), VenueBookingStatus.cancelled.name())));

        List<UUID> venueStaff = users.findByRole(UserRole.vs).stream().map(User::getUserId).toList();
        if (!venueStaff.isEmpty()) {
            afterCommit(() -> notificationService.createVenueBookingCancelledNotifications(
                    venueStaff, saved.getBookingId(), eventId, event.getEventName(), why));
        }
        return toDto(saved);
    }

    private VenueOptionDto option(Event event, Venue venue, List<VenueOptionDto.ConflictDto> conflicts,
            List<VenueOptionDto.UnavailablePeriodDto> unavailable,
            List<VenueOptionDto.UnavailablePeriodDto> hoursUnavailable,
            OffsetDateTime start, OffsetDateTime end, com.example.connect_sphere.venueavailability.AvailabilityModels.Settings durations) {
        VenueSuitability suitability = VenueSuitability.of(event, venue);
        List<String> reasons = new ArrayList<>();
        var recorded = availability.unavailablePeriods(venue.getVenueId(), start, end, durations);
        boolean recordedUnavailable = !recorded.isEmpty();
        // Say when and why, as booking conflicts already do (EC03).
        recorded.forEach(period -> reasons.add("Venue unavailable " + describe(period) + "."));
        if (!suitability.capacityOk()) {
            reasons.add("Capacity " + suitability.capacity() + " is below the expected attendance of "
                    + suitability.expectedAttendance() + ".");
        }
        for (VenueOptionDto.ConflictDto conflict : conflicts) {
            reasons.add(conflict.sentence() + ".");
        }
        for (VenueOptionDto.UnavailablePeriodDto period : unavailable) {
            reasons.add("Venue unavailable: " + period.description() + ".");
        }
        reasons.addAll(hoursUnavailable.stream().map(p -> "Operating hours: " + p.description() + ".").toList());
        if (suitability.needsJustification()) {
            reasons.add("Missing accessibility: " + suitability.missingAccessibilityLabels() + ".");
        }
        if (!suitability.facilitiesOk()) {
            reasons.add("Missing facilities: " + suitability.missingFacilityLabels() + ".");
        }
        String verdict = recordedUnavailable || !suitability.capacityOk() || !suitability.facilitiesOk()
                || !conflicts.isEmpty() || !unavailable.isEmpty() || !hoursUnavailable.isEmpty() ? "blocked"
                : suitability.needsJustification() ? "needs_justification"
                : "suitable";
        return new VenueOptionDto(venueMapper.toDto(venue), verdict, suitability.capacityOk(),
                suitability.capacity() - suitability.expectedAttendance(), suitability.missingAccessibility(),
                suitability.missingFacilities(),
                java.util.stream.Stream.concat(unavailable.stream(), hoursUnavailable.stream()).toList(),
                conflicts, reasons);
    }

    private List<VenueOptionDto.ConflictDto> conflicts(Event event, Venue venue, OffsetDateTime start, OffsetDateTime end,
            com.example.connect_sphere.venueavailability.AvailabilityModels.Settings durations) {
        return availability.conflicts(venue.getVenueId(), event.getEventId(), start, end, durations)
                .stream().map(c -> new VenueOptionDto.ConflictDto(VenueOptionDto.ConflictDto.ANOTHER_EVENT,
                        c.effectiveStart(), c.effectiveEnd())).toList();
    }

    private static com.example.connect_sphere.venueavailability.AvailabilityModels.Settings requestedDurations(
            Venue venue, java.math.BigDecimal setup, java.math.BigDecimal turnaround) {
        return new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(
                com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(setup, venue.getSetupMinutes()),
                com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(turnaround, venue.getTurnaroundMinutes()));
    }

    private Map<UUID, List<VenueOptionDto.UnavailablePeriodDto>> unavailableByVenue(OffsetDateTime start, OffsetDateTime end,
            java.math.BigDecimal setup, java.math.BigDecimal turnaround) {
        if (operationalIssues == null) return Map.of();
        if (start == null || end == null) {
            return Map.of();
        }
        var periods = venues.findAll().stream().collect(Collectors.toMap(Venue::getVenueId,
                venue -> com.example.connect_sphere.venueavailability.EffectivePeriod.of(start,
                        end, requestedDurations(venue, setup, turnaround))));
        return operationalIssues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(List.copyOf(periods.keySet()))
                .stream()
                .filter(issue -> issue.getAffectedFrom() != null && issue.getAffectedUntil() != null
                        && periods.containsKey(issue.getVenueId())
                        && issue.getAffectedFrom().isBefore(periods.get(issue.getVenueId()).end())
                        && issue.getAffectedUntil().isAfter(periods.get(issue.getVenueId()).start()))
                .collect(Collectors.groupingBy(
                        VenueOperationalIssue::getVenueId,
                        Collectors.mapping(issue -> new VenueOptionDto.UnavailablePeriodDto(
                                issue.getDescription(), issue.getAffectedFrom(), issue.getAffectedUntil()),
                                Collectors.toList())));
    }

    private Map<UUID, List<VenueOptionDto.UnavailablePeriodDto>> hoursByVenue(OffsetDateTime start, OffsetDateTime end,
            java.math.BigDecimal setup, java.math.BigDecimal turnaround) {
        if (operatingHours == null) return Map.of();
        if (start == null || end == null) return Map.of();
        var venueList = venues.findAll();
        List<UUID> ids = venueList.stream().map(Venue::getVenueId).toList();
        var periods = venueList.stream().collect(Collectors.toMap(Venue::getVenueId,
                venue -> com.example.connect_sphere.venueavailability.EffectivePeriod.of(start,
                        end, requestedDurations(venue, setup, turnaround))));
        Map<UUID, List<VenueOperatingHour>> grouped = operatingHours.findByVenueIdIn(ids).stream()
                .collect(Collectors.groupingBy(VenueOperatingHour::getVenueId));
        return ids.stream().filter(id -> grouped.getOrDefault(id, List.of()).stream().noneMatch(hour ->
                com.example.connect_sphere.venueavailability.OperatingHours.covers(hour.getDayOfWeek(), hour.getOpenTime(), hour.getCloseTime(), periods.get(id))))
                .collect(Collectors.toMap(id -> id, id -> List.of(new VenueOptionDto.UnavailablePeriodDto(
                        grouped.containsKey(id) ? "No operating hours cover the full occupied period, including setup and turnaround"
                                : "No operating hours have been configured",
                        null, null))));
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
    private static final java.time.format.DateTimeFormatter SGT = java.time.format.DateTimeFormatter
            .ofPattern("d MMM yyyy, hh:mm a", java.util.Locale.ENGLISH).withZone(java.time.ZoneId.of("Asia/Singapore"));

    /** "15 Feb 2027, 08:00 am – 15 Feb 2027, 06:00 pm (Singapore time): Carpet replacement", as the screens write times. */
    private static String describe(com.example.connect_sphere.venueavailability.AvailabilityModels.Period period) {
        String when = sgt(period.startDateTime()) + " – " + sgt(period.endDateTime()) + " (Singapore time)";
        return period.reason() == null || period.reason().isBlank() ? when : when + ": " + period.reason();
    }

    private static String sgt(OffsetDateTime time) {
        return SGT.format(time).replace(" AM", " am").replace(" PM", " pm");
    }

    /** The status people see (EC-NEW2), not the stored code. */
    private static String stageLabel(EventStatus status) {
        return status == EventStatus.pending ? JourneyStatus.PLANNING.label()
                : JourneyStatus.valueOf(status.name().toUpperCase(java.util.Locale.ROOT)).label();
    }

    /**
     * The timeline entry for a new request. The full notes stay on the
     * booking; here they are shortened if needed so the entry fits the
     * timeline's 2000-character limit (a long note used to fail the whole
     * submission).
     */
    static String requestedMessage(String venueAddress, String notes) {
        String head = "Requested " + venueAddress;
        if (notes == null) {
            return head;
        }
        String message = head + ". " + notes;
        return message.length() <= MAX_TEXT_LENGTH ? message : message.substring(0, MAX_TEXT_LENGTH - 1) + "…";
    }

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
        Venue alternativeVenue = b.getAlternativeVenueId() == null ? null
                : venues.findById(b.getAlternativeVenueId()).orElse(null);
        String submittedBy = b.getSubmittedBy() == null ? null
                : users.findById(b.getSubmittedBy()).map(User::getUsername).orElse(null);
        Event event = events.findById(b.getEventId()).orElse(null);
        return new EventVenueBookingDto(b.getBookingId(), b.getStatus(), b.getVenueId(),
                venue == null ? null : venue.getVenueAddress(), venue == null ? null : venue.getVenueCapacity(),
                b.getBookingNotes(), b.getSuitabilityNote(), b.getRejectReason(), b.getSubmittedAt(), submittedBy,
                availability.affected(b.getBookingId()), b.getReplacesBookingId(),
                b.getAlternativeVenueId(), alternativeVenue == null ? null : alternativeVenue.getVenueAddress(),
                b.getAlternativeArrangement(),
                b.getStartDatetime() == null && event != null ? event.getStartDatetime() : b.getStartDatetime(),
                b.getEndDatetime() == null && event != null ? event.getEndDatetime() : b.getEndDatetime(),
                event == null ? null : availability.timing(
                        b.getVenueId(), b.getBookingId(),
                        b.getStartDatetime() == null ? event.getStartDatetime() : b.getStartDatetime(),
                        b.getEndDatetime() == null ? event.getEndDatetime() : b.getEndDatetime(),
                        b.getSetupDurationMinutes(), b.getTurnaroundDurationMinutes(), b.getTimingNote(),
                        ACTIVE.contains(b.getStatus()) && event.getStatus() != EventStatus.completed
                        && event.getStatus() != EventStatus.cancelled), holds == null ? List.of() : holds.history(b.getBookingId()));
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
