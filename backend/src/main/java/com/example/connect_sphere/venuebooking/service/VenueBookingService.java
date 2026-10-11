package com.example.connect_sphere.venuebooking.service;

import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.dto.VenueBookingDto;
import com.example.connect_sphere.venuebooking.dto.VenueBookingConflictDto;
import com.example.connect_sphere.venuebooking.dto.VenueUnavailabilityConflictDto;
import com.example.connect_sphere.venuebooking.entity.VenueBooking;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRepository;

@Service
@Transactional(readOnly = true)
public class VenueBookingService {
    private final com.example.connect_sphere.venueavailability.AvailabilityService availability;
    private static final int MAX_TEXT_LENGTH = 2000;

    private final VenueBookingRepository bookings;
    private final VenueRepository venues;
    private final VenueMapper venueMapper;
    private final VenueBookingRecordRepository records;
    private final EventRepository events;

    public VenueBookingService(VenueBookingRepository bookings, VenueRepository venues, VenueMapper venueMapper,
            VenueBookingRecordRepository records,
            EventRepository events, com.example.connect_sphere.venueavailability.AvailabilityService availability) {
        this.availability = availability;
        this.bookings = bookings;
        this.venues = venues;
        this.venueMapper = venueMapper;
        this.records = records;
        this.events = events;
    }

    /** VS03: event lock matches submission/cancellation; venue lock serialises competing approvals.
     * Ownership is not modelled yet; the API restricts this action to Venue Staff. */
    @Transactional
    public VenueBookingDto approve(UUID bookingId) {
        UUID eventId = records.findEventId(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var event = events.findForUpdate(eventId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var booking = records.findById(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        if (booking.getStatus() != VenueBookingStatus.pending) {
            throw new VenueBookingStateException("Only pending bookings can be approved. This booking is "
                    + booking.getStatus().name() + ".");
        }
        // Lock both venues in stable order when releasing an original commitment.
        var venueIds = new java.util.TreeSet<UUID>();
        venueIds.add(booking.getVenueId());
        if (booking.getReplacesBookingId() != null) {
            var original = records.findById(booking.getReplacesBookingId())
                    .orElseThrow(() -> new VenueBookingStateException("Original booking no longer exists."));
            venueIds.add(original.getVenueId());
        }
        for (UUID venueId : venueIds) venues.findForUpdate(venueId).orElseThrow(() -> new VenueNotFoundException(venueId));
        var conflicts = records.findApprovalConflicts(booking.getVenueId(), bookingId,
                VenueBookingStatus.approved, event.getStartDatetime(), event.getEndDatetime());
        var unavailablePeriods = availability.unavailablePeriods(booking.getVenueId(), event.getStartDatetime(), event.getEndDatetime())
                .stream().map(period -> new VenueUnavailabilityConflictDto(period.unavailabilityId(), period.reason(),
                        period.startDateTime(), period.endDateTime())).toList();
        var bufferConflicts = availability.conflicts(booking.getVenueId(), eventId, event.getStartDatetime(), event.getEndDatetime());
        var blockingBookings = new java.util.LinkedHashMap<UUID, VenueBookingConflictDto>();
        conflicts.forEach(conflict -> blockingBookings.put(conflict.getBookingId(), new VenueBookingConflictDto(
                conflict.getBookingId(), conflict.getEventName(), conflict.getStartDatetime(), conflict.getEndDatetime())));
        bufferConflicts.forEach(conflict -> blockingBookings.putIfAbsent(conflict.bookingId(), new VenueBookingConflictDto(
                conflict.bookingId(), conflict.eventName(), conflict.startDateTime(), conflict.endDateTime())));
        if (!blockingBookings.isEmpty() || !unavailablePeriods.isEmpty()) {
            String message = !unavailablePeriods.isEmpty()
                    ? blockingBookings.isEmpty()
                        ? "The venue has recorded unavailable periods during the event, setup or turnaround period."
                        : "This request conflicts with approved bookings and recorded unavailable periods."
                    : !conflicts.isEmpty()
                        ? "This booking overlaps an existing approved booking for the selected venue."
                        : "This booking conflicts with the setup or turnaround time required around an existing approved booking.";
            var orderedBookings = blockingBookings.values().stream()
                    .sorted(java.util.Comparator.comparing((VenueBookingConflictDto conflict) -> conflict.startDatetime().toInstant())
                            .thenComparing(VenueBookingConflictDto::bookingId)).toList();
            throw new VenueBookingConflictException(message, orderedBookings, unavailablePeriods);
        }
        if (booking.getReplacesBookingId() != null) {
            var original = records.findById(booking.getReplacesBookingId()).orElseThrow(() -> new VenueBookingStateException("Original booking no longer exists."));
            if (!original.getEventId().equals(eventId) || !availability.affected(original.getBookingId()))
                throw new VenueBookingStateException("The original booking no longer requires replacement. Refresh this event.");
            original.setStatus(VenueBookingStatus.changed);
            records.saveAndFlush(original);
            event.setVenueId(booking.getVenueId());
            events.saveAndFlush(event);
        }
        if (records.approvePending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.approved) != 1) {
            throw new VenueBookingStateException("This booking is no longer pending. Refresh its details.");
        }
        return get(bookingId);
    }

    /** VS04: use the same event lock as approval, submission and cancellation. */
    @Transactional
    public VenueBookingDto reject(UUID bookingId, String reason, UUID alternativeVenueId, String alternativeArrangement) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidVenueBookingException("A rejection reason is required.");
        }
        String trimmedReason = reason.strip();
        String trimmedArrangement = optionalText(alternativeArrangement, "Alternative arrangement");
        UUID eventId = records.findEventId(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        events.findForUpdate(eventId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var booking = records.findById(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        venues.findForUpdate(booking.getVenueId()).orElseThrow(() -> new VenueNotFoundException(booking.getVenueId()));
        if (booking.getStatus() != VenueBookingStatus.pending) {
            throw new VenueBookingStateException("Only pending bookings can be rejected. This booking is "
                    + booking.getStatus().name() + ".");
        }
        if (records.existsByReplacesBookingIdAndStatus(bookingId, VenueBookingStatus.pending))
            throw new VenueBookingStateException("Resolve the pending replacement request before rejecting the original booking.");
        if (alternativeVenueId != null) {
            if (alternativeVenueId.equals(booking.getVenueId())) {
                throw new InvalidVenueBookingException("The alternative venue must differ from the requested venue.");
            }
            venues.findById(alternativeVenueId)
                    .orElseThrow(() -> new VenueNotFoundException(alternativeVenueId));
        }
        if (records.rejectPending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.rejected, trimmedReason,
                alternativeVenueId, trimmedArrangement) != 1) {
            throw new VenueBookingStateException("This booking is no longer pending. Refresh its details.");
        }
        return get(bookingId);
    }

    public List<VenueBookingDto> listPending() {
        return bookings.findByStatusOrderByEventStartDatetimeAscBookingIdAsc(VenueBookingStatus.pending)
                .stream().map(this::toDto).toList();
    }

    public VenueBookingDto get(UUID bookingId) {
        return toDto(bookings.findById(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId)));
    }

    public List<VenueBookingDto> listForVenue(UUID venueId) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return bookings.findByVenueVenueIdOrderByEventStartDatetimeAscBookingIdAsc(venueId)
                .stream().map(this::toDto).toList();
    }

    private VenueBookingDto toDto(VenueBooking booking) {
        var event = booking.getEvent();
        var alternativeVenue = booking.getAlternativeVenue();
        return new VenueBookingDto(booking.getBookingId(), booking.getStatus(),
                venueMapper.toDto(booking.getVenue()), new VenueBookingDto.EventRequirements(
                        event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                        event.getExpectedAttendance(), event.getVenueRequirements(),
                        List.copyOf(event.getAccessibilityNeeds()), event.getEquipmentRequirements()),
                booking.getBookingNotes(), booking.getSuitabilityNote(), booking.getSubmittedAt(), booking.getRejectReason(),
                availability.affected(booking.getBookingId()), booking.getReplacesBookingId(),
                booking.getAlternativeVenueId(),
                alternativeVenue == null ? null : alternativeVenue.getVenueAddress(),
                booking.getAlternativeArrangement());
    }

    private static String optionalText(String raw, String what) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidVenueBookingException(what + " can be at most " + MAX_TEXT_LENGTH + " characters.");
        }
        return text;
    }
}
