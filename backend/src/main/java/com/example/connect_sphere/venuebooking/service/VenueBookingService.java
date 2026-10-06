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
import com.example.connect_sphere.venuebooking.entity.VenueBooking;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRepository;

@Service
@Transactional(readOnly = true)
public class VenueBookingService {
    private final com.example.connect_sphere.venueavailability.AvailabilityService availability;
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
        if (availability.unavailable(booking.getVenueId(), event.getStartDatetime(), event.getEndDatetime())) {
            throw new VenueBookingStateException("The venue is unavailable during the event, setup or turnaround period.");
        }
        if (!availability.conflicts(booking.getVenueId(), eventId, event.getStartDatetime(), event.getEndDatetime()).isEmpty()) {
            throw new VenueBookingStateException("This venue already has an approved booking at the requested time.");
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
    public VenueBookingDto reject(UUID bookingId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidVenueBookingException("A rejection reason is required.");
        }
        String trimmedReason = reason.strip();
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
        if (records.rejectPending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.rejected, trimmedReason) != 1) {
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
        return new VenueBookingDto(booking.getBookingId(), booking.getStatus(),
                venueMapper.toDto(booking.getVenue()), new VenueBookingDto.EventRequirements(
                        event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                        event.getExpectedAttendance(), event.getVenueRequirements(),
                        List.copyOf(event.getAccessibilityNeeds()), event.getEquipmentRequirements()),
                booking.getBookingNotes(), booking.getSuitabilityNote(), booking.getSubmittedAt(), booking.getRejectReason(),
                availability.affected(booking.getBookingId()), booking.getReplacesBookingId());
    }
}
