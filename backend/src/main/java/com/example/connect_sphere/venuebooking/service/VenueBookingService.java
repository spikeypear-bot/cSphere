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
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final com.example.connect_sphere.venueavailability.TimingConflictService timingConflicts;
    private final com.example.connect_sphere.venueavailability.AvailabilityService availability;
    private static final int MAX_TEXT_LENGTH = 2000;

    private final VenueBookingRepository bookings;
    private final VenueRepository venues;
    private final VenueMapper venueMapper;
    private final VenueBookingRecordRepository records;
    private final EventRepository events;

    public VenueBookingService(VenueBookingRepository bookings, VenueRepository venues, VenueMapper venueMapper,
            VenueBookingRecordRepository records,
            EventRepository events, com.example.connect_sphere.venueavailability.AvailabilityService availability,
            com.example.connect_sphere.venueavailability.TimingConflictService timingConflicts) {
        this.timingConflicts = timingConflicts;
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
        var start = booking.getStartDatetime() == null ? event.getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? event.getEndDatetime() : booking.getEndDatetime();
        var conflicts = records.findApprovalConflicts(booking.getVenueId(), bookingId,
                VenueBookingStatus.approved, start, end);
        var durations = new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(
                booking.getSetupDurationMinutes(), booking.getTurnaroundDurationMinutes());
        var unavailablePeriods = availability.unavailablePeriods(booking.getVenueId(), start, end, durations)
                .stream().map(period -> new VenueUnavailabilityConflictDto(period.unavailabilityId(), period.reason(),
                        period.startDateTime(), period.endDateTime())).toList();
        var bufferConflicts = availability.conflictsForBooking(booking.getVenueId(), bookingId, start, end, durations);
        var blockingBookings = new java.util.LinkedHashMap<UUID, VenueBookingConflictDto>();
        conflicts.forEach(conflict -> blockingBookings.put(conflict.getBookingId(), VenueBookingConflictDto.of(
                conflict.getBookingId(), conflict.getEventName(), conflict.getStartDatetime(), conflict.getEndDatetime(),
                conflict.getSetupDurationMinutes(), conflict.getTurnaroundDurationMinutes())));
        bufferConflicts.forEach(conflict -> blockingBookings.putIfAbsent(conflict.bookingId(), VenueBookingConflictDto.from(conflict)));
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
        if (availability.hasOperationalIssue(booking.getVenueId(), start, end, durations))
            throw new VenueBookingStateException("A venue operational issue overlaps the occupied booking period.");
        if (availability.outsideOperatingHours(booking.getVenueId(), start, end, durations))
            throw new VenueBookingStateException("The full occupied period, including setup and turnaround, must fit within configured venue opening hours.");
        if (booking.getReplacesBookingId() != null) {
            var original = records.findById(booking.getReplacesBookingId()).orElseThrow(() -> new VenueBookingStateException("Original booking no longer exists."));
            if (!original.getEventId().equals(eventId)
                    || !isEligibleReplacementSource(original)
                    || records.existsByReplacesBookingIdAndStatusIn(original.getBookingId(),
                            List.of(VenueBookingStatus.approved))) {
                throw new VenueBookingStateException("The original booking is no longer eligible for replacement. Refresh this event.");
            }
            if (original.getStatus() == VenueBookingStatus.pending || original.getStatus() == VenueBookingStatus.approved) {
                original.setStatus(VenueBookingStatus.changed);
                records.saveAndFlush(original);
            }
            event.setVenueId(booking.getVenueId());
            events.saveAndFlush(event);
        }
        if (records.approvePending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.approved) != 1) {
            throw new VenueBookingStateException("This booking is no longer pending. Refresh its details.");
        }
        return get(bookingId);
    }

    private boolean isEligibleReplacementSource(com.example.connect_sphere.venuebooking.entity.VenueBookingRecord booking) {
        if (booking.getStatus() == VenueBookingStatus.rejected || booking.getStatus() == VenueBookingStatus.cancelled) {
            return true;
        }
        return (booking.getStatus() == VenueBookingStatus.pending || booking.getStatus() == VenueBookingStatus.approved)
                && availability.affected(booking.getBookingId());
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

    @Transactional
    public VenueBookingDto updateTiming(UUID bookingId, UUID actor,
            com.example.connect_sphere.venuebooking.dto.UpdateBookingTimingRequest request) {
        UUID eventId = records.findEventId(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var event = events.findForUpdate(eventId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var booking = records.findById(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        venues.findForUpdate(booking.getVenueId()).orElseThrow(() -> new VenueNotFoundException(booking.getVenueId()));
        if ((booking.getStatus() != VenueBookingStatus.pending && booking.getStatus() != VenueBookingStatus.approved)
                || event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.cancelled
                || event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.completed)
            throw new VenueBookingStateException("Only active pending or approved booking timings can be edited.");
        if (request == null || request.setupDurationMinutes() == null || request.turnaroundDurationMinutes() == null)
            throw new InvalidVenueBookingException("Both setup and turnaround durations are required.");
        if (!java.util.Objects.equals(request.previousSetupDurationMinutes(), booking.getSetupDurationMinutes())
                || !java.util.Objects.equals(request.previousTurnaroundDurationMinutes(), booking.getTurnaroundDurationMinutes()))
            throw new VenueBookingStateException("Booking timings changed. Refresh the booking before saving again.");
        int setup = com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(request.setupDurationMinutes(), 0);
        int turnaround = com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(request.turnaroundDurationMinutes(), 0);
        String note = optionalText(request.timingNote(), "Timing reason");
        if (note == null) throw new InvalidVenueBookingException("Explain the reason for these booking timings.");
        var start = booking.getStartDatetime() == null ? event.getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? event.getEndDatetime() : booking.getEndDatetime();
        var durations = new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(setup, turnaround);
        if (availability.hasOperationalIssue(booking.getVenueId(), start, end, durations))
            throw new VenueBookingStateException("A venue operational issue overlaps the occupied booking period.");
        if (availability.outsideOperatingHours(booking.getVenueId(), start, end, durations))
            throw new VenueBookingStateException("The full occupied period, including setup and turnaround, must fit within configured venue opening hours.");
        var overlaps = availability.conflictsForBooking(booking.getVenueId(), bookingId, start, end, durations);
        var periods = availability.unavailablePeriods(booking.getVenueId(), start, end, durations);
        boolean reducing = setup <= booking.getSetupDurationMinutes() && turnaround <= booking.getTurnaroundDurationMinutes();
        if (!reducing && (!overlaps.isEmpty() || !periods.isEmpty()))
            throw new VenueBookingConflictException("These timings would introduce or extend a conflict. Reduce the occupied period or resolve the conflicting arrangements first.",
                    overlaps.stream().map(VenueBookingConflictDto::from).toList(),
                    periods.stream().map(p -> new VenueUnavailabilityConflictDto(p.unavailabilityId(), p.reason(), p.startDateTime(), p.endDateTime())).toList());
        if (setup == booking.getSetupDurationMinutes() && turnaround == booking.getTurnaroundDurationMinutes()
                && java.util.Objects.equals(note, booking.getTimingNote())) return get(bookingId);
        booking.setSetupDurationMinutes(setup);
        booking.setTurnaroundDurationMinutes(turnaround);
        booking.setTimingNote(note);
        booking.setTimingUpdatedAt(java.time.OffsetDateTime.now());
        booking.setTimingUpdatedBy(actor);
        records.saveAndFlush(booking);
        var period = com.example.connect_sphere.venueavailability.EffectivePeriod.of(start, end, durations);
        timingConflicts.notifyCoordinator(event.getCoordinatorId(), eventId, bookingId, event.getEventName(), "venue_timing_changed",
                "Venue Staff saved setup " + setup + " minutes and turnaround " + turnaround + " minutes. Occupied: "
                        + period.start() + " to " + period.end() + ". Reason: " + note);
        timingConflicts.scan(booking.getVenueId());
        entityManager.clear();
        return get(bookingId);
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
        var start = booking.getStartDatetime() == null ? event.getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? event.getEndDatetime() : booking.getEndDatetime();
        return new VenueBookingDto(booking.getBookingId(), booking.getStatus(),
                venueMapper.toDto(booking.getVenue()), new VenueBookingDto.EventRequirements(
                        event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                        event.getExpectedAttendance(), event.getVenueRequirements(),
                        List.copyOf(event.getAccessibilityNeeds()), event.getEquipmentRequirements()),
                booking.getBookingNotes(), booking.getSuitabilityNote(), booking.getSubmittedAt(), booking.getRejectReason(),
                availability.affected(booking.getBookingId()), booking.getReplacesBookingId(),
                booking.getAlternativeVenueId(),
                alternativeVenue == null ? null : alternativeVenue.getVenueAddress(),
                booking.getAlternativeArrangement(), availability.timing(booking.getVenue().getVenueId(), booking.getBookingId(),
                        start, end, booking.getSetupDurationMinutes(),
                        booking.getTurnaroundDurationMinutes(), booking.getTimingNote(),
                        (booking.getStatus() == VenueBookingStatus.pending || booking.getStatus() == VenueBookingStatus.approved)
                        && event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.completed
                        && event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.cancelled));
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
