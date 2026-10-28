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
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.connect_sphere.venuebooking.hold.VenueHoldRepository holds;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.connect_sphere.venuebooking.hold.VenueHoldService holdService;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.example.connect_sphere.changerequest.ChangeImpactService impact;
    @org.springframework.beans.factory.annotation.Autowired private BookingPlanService plan;
    @org.springframework.beans.factory.annotation.Autowired private com.example.connect_sphere.event.service.EventLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired private com.example.connect_sphere.notification.service.NotificationService notifications;
    private static final int MAX_TEXT_LENGTH = 2000;

    private final VenueBookingRepository bookings;
    private final VenueRepository venues;
    private final VenueMapper venueMapper;
    private final VenueBookingRecordRepository records;
    private final EventRepository events;
    @org.springframework.beans.factory.annotation.Autowired private com.example.connect_sphere.user.repository.UserRepository users;

    /** EC-NEW5: announces changes for the event change log (ChangeLogRecorder).
     * Setter-injected so this service does not depend on the log; absent in
     * plain unit tests, where nothing is announced. */
    private org.springframework.context.ApplicationEventPublisher changeLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChangeLog(org.springframework.context.ApplicationEventPublisher changeLog) {
        this.changeLog = changeLog;
    }

    private void announce(UUID eventId, com.example.connect_sphere.activity.entity.ActivityType type, UUID actor,
            String message) {
        if (changeLog != null) {
            // Audit summaries have a 2000-character contract, including labels and addresses.
            if (message != null && message.length()>MAX_TEXT_LENGTH)
                message=message.substring(0,MAX_TEXT_LENGTH-3)+"...";
            changeLog.publishEvent(new com.example.connect_sphere.activity.service.EventChange(eventId, type, actor, message));
        }
    }

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
    public VenueBookingDto approve(UUID bookingId) { return approve(bookingId, null); }

    @Transactional
    public VenueBookingDto approve(UUID bookingId, UUID convertingHoldId) {
        plan.actor(null);
        UUID eventId = records.findEventId(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var event = events.findForUpdate(eventId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        if (event.getStatus()==com.example.connect_sphere.event.entity.EventStatus.cancelled
                || event.getStatus()==com.example.connect_sphere.event.entity.EventStatus.completed)
            throw new VenueBookingStateException("Bookings for completed or cancelled events cannot be approved.");
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
        var fit = VenueSuitability.of(event, venues.findById(booking.getVenueId()).orElseThrow(), booking);
        if (fit.needsJustification() && (booking.getSuitabilityNote() == null || booking.getSuitabilityNote().isBlank()))
            throw new VenueBookingStateException("Current venue accessibility is missing: " + fit.missingAccessibilityLabels()
                    + ". A coordinator suitability justification is required before approval.");
        if (holds != null) {
            if (convertingHoldId != null) {
                holds.requireActive(convertingHoldId, bookingId);
                if (!event.getStatus().isPlanningEditable())
                    throw new VenueBookingStateException("Hold conversion requires an event in Planning.");
            } else if (holds.hasActive(bookingId)) {
                throw new VenueBookingStateException("Use Convert hold to approve this reserved request.");
            }
        }
        // Validate before excluding the original from conflict checks. Both rows remain
        // active until approval succeeds; the event and venue locks cover the swap.
        if (booking.getReplacesBookingId() != null) {
            var original = records.findById(booking.getReplacesBookingId()).orElseThrow();
            plan.requireReplacementEligible(original.getBookingId(), bookingId);
            if (!original.getEventId().equals(eventId) || !isEligibleReplacementSource(original))
                throw new VenueBookingStateException("The original booking no longer requires replacement. Refresh this event.");
        }
        var start = booking.getStartDatetime() == null ? event.getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? event.getEndDatetime() : booking.getEndDatetime();
        if (convertingHoldId != null) {
            var hold = holds.history(bookingId).stream().filter(h -> h.holdId().equals(convertingHoldId)).findFirst().orElseThrow();
            var occupied = com.example.connect_sphere.venueavailability.EffectivePeriod.of(start, end,
                    new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(
                            booking.getSetupDurationMinutes(), booking.getTurnaroundDurationMinutes()));
            if (!hold.reservedStart().toInstant().equals(occupied.start().toInstant())
                    || !hold.reservedEnd().toInstant().equals(occupied.end().toInstant()))
                throw new VenueBookingStateException("The booking period changed since this hold was placed. Release it and review the arrangement.");
        }
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
        if (booking.getReplacesBookingId() != null) blockingBookings.remove(booking.getReplacesBookingId());
        if (!blockingBookings.isEmpty() || !unavailablePeriods.isEmpty()) {
            String message = bufferConflicts.stream().anyMatch(b -> b.status().equals("hold"))
                    ? "This booking conflicts with an active tentative hold during the occupied period."
                    : !unavailablePeriods.isEmpty()
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
        String replacedVenue = null;
        if (booking.getReplacesBookingId() != null) {
            var original = records.findById(booking.getReplacesBookingId()).orElseThrow(() -> new VenueBookingStateException("Original booking no longer exists."));
            replacedVenue = venues.findById(original.getVenueId()).map(com.example.connect_sphere.venue.entity.Venue::getVenueAddress).orElse(null);
            if (!original.getEventId().equals(eventId)
                    || !isEligibleReplacementSource(original)
                    || records.existsByReplacesBookingIdAndStatusIn(original.getBookingId(),
                            List.of(VenueBookingStatus.approved, VenueBookingStatus.attention))) {
                throw new VenueBookingStateException("The original booking is no longer eligible for replacement. Refresh this event.");
            }
            if (original.getStatus() == VenueBookingStatus.pending || original.getStatus() == VenueBookingStatus.approved || original.getStatus() == VenueBookingStatus.attention) {
                if (holdService != null) holdService.endForBooking(original.getBookingId(), null);
                original.setStatus(VenueBookingStatus.changed);
                records.saveAndFlush(original);
            }
            // Active venue bookings are authoritative; there is no primary venue assignment.
        }
        if (convertingHoldId != null) {
            holds.requireActive(convertingHoldId, bookingId);
            holds.transition(convertingHoldId, "converted", holds.now());
            announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.venue_hold_converted, null,
                "Tentative hold converted into an approved venue booking.");
        }
        if (impact != null && impact.assessBooking(event,booking).stream().anyMatch(c -> "attention".equals(c.status()) && (c.key().endsWith("capacity") || c.key().endsWith("facilities"))))
            throw new VenueBookingStateException("The venue no longer meets the approved event requirements. Review current suitability.");
        if (records.approvePending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.approved) != 1) {
            throw new VenueBookingStateException("This booking is no longer pending. Refresh its details.");
        }
        VenueBookingDto approved = get(bookingId);
        notifications.createVenueBookingApprovedNotification(event.getCoordinatorId(), bookingId, eventId,
                event.getEventName(), approved.venue().venueAddress());
        if (replacedVenue != null && changeLog != null) {
            // EO-C1: approved, not yet confirmed; the follow-up comes on re-confirmation.
            changeLog.publishEvent(new com.example.connect_sphere.venueavailability.VenueArrangementChanged(eventId,
                    List.of(bookingId), "A replacement venue was approved for your event: " + approved.venue().venueAddress()
                            + ", replacing " + replacedVenue + ".", null));
        }
        announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.venue_booking_approved, null,
                "Approved " + approved.venue().venueAddress());
        return approved;
    }

    private boolean isEligibleReplacementSource(com.example.connect_sphere.venuebooking.entity.VenueBookingRecord booking) {
        if (booking.getStatus() == VenueBookingStatus.rejected || booking.getStatus() == VenueBookingStatus.cancelled) {
            return true;
        }
        return (booking.getStatus() == VenueBookingStatus.pending || booking.getStatus() == VenueBookingStatus.approved || booking.getStatus() == VenueBookingStatus.attention)
                && (booking.getStatus() == VenueBookingStatus.approved || booking.getStatus() == VenueBookingStatus.attention || availability.affected(booking.getBookingId()));
    }

    /** VS04: use the same event lock as approval, submission and cancellation. */
    @Transactional
    public VenueBookingDto reject(UUID bookingId, String reason, UUID alternativeVenueId, String alternativeArrangement) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidVenueBookingException("A rejection reason is required.");
        }
        String trimmedReason = reason.strip();
        if (trimmedReason.length() > MAX_TEXT_LENGTH)
            throw new InvalidVenueBookingException("Rejection reason must be at most 2000 characters.");
        String trimmedArrangement = optionalText(alternativeArrangement, "Alternative arrangement");
        plan.actor(null);
        UUID eventId = records.findEventId(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var event=events.findForUpdate(eventId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        if (event.getStatus()==com.example.connect_sphere.event.entity.EventStatus.cancelled
                || event.getStatus()==com.example.connect_sphere.event.entity.EventStatus.completed)
            throw new VenueBookingStateException("Bookings for completed or cancelled events cannot be rejected.");
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
        if (holdService != null) holdService.endForBooking(bookingId, null);
        if (records.rejectPending(bookingId, VenueBookingStatus.pending, VenueBookingStatus.rejected, trimmedReason,
                alternativeVenueId, trimmedArrangement) != 1) {
            throw new VenueBookingStateException("This booking is no longer pending. Refresh its details.");
        }
        VenueBookingDto rejected = get(bookingId);
        notifications.createVenueBookingRejectedNotification(event.getCoordinatorId(),bookingId,eventId,
                event.getEventName(),rejected.venue().venueAddress(),trimmedReason,
                rejected.alternativeVenueAddress() != null
                        ? "Suggested instead: " + rejected.alternativeVenueAddress() + (trimmedArrangement != null ? ". " + trimmedArrangement : "")
                        : trimmedArrangement);
        announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.venue_booking_rejected, null,
                "Rejected " + rejected.venue().venueAddress() + ": " + trimmedReason);
        if (rejected.alternativeVenueAddress() != null || trimmedArrangement != null) {
            announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.venue_alternative_suggested, null,
                    rejected.alternativeVenueAddress() != null
                            ? "Suggested " + rejected.alternativeVenueAddress() + " instead"
                                    + (trimmedArrangement != null ? ". " + trimmedArrangement : "")
                            : "Suggested an alternative arrangement: " + trimmedArrangement);
        }
        return rejected;
    }

    public List<java.util.Map<String,Object>> history(UUID bookingId) {
        get(bookingId); return plan.history(bookingId);
    }

    public List<VenueBookingDto> listPending() {
        return java.util.stream.Stream.concat(bookings.findByStatusOrderByEventStartDatetimeAscBookingIdAsc(VenueBookingStatus.pending).stream(),
                bookings.findByStatusOrderByEventStartDatetimeAscBookingIdAsc(VenueBookingStatus.attention).stream()).map(this::toDto).toList();
    }

    @Transactional
    public VenueBookingDto updateTiming(UUID bookingId, UUID actor,
            com.example.connect_sphere.venuebooking.dto.UpdateBookingTimingRequest request) {
        plan.actor(null);
        UUID eventId = records.findEventId(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var event = events.findForUpdate(eventId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var booking = records.findById(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        venues.findForUpdate(booking.getVenueId()).orElseThrow(() -> new VenueNotFoundException(booking.getVenueId()));
        if ((booking.getStatus() != VenueBookingStatus.pending && booking.getStatus() != VenueBookingStatus.approved && booking.getStatus() != VenueBookingStatus.attention)
                || event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.cancelled
                || event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.completed)
            throw new VenueBookingStateException("Only active pending or approved booking timings can be edited.");
        if (holds != null && holds.hasActive(bookingId))
            throw new VenueBookingStateException("Release the active tentative hold before changing booking timings.");
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
        lifecycle.returnToPlanning(eventId,actor,"Venue booking occupied times changed; reassess safety arrangements.");
        if (booking.getStatus() != VenueBookingStatus.pending && changeLog != null) {
            changeLog.publishEvent(new com.example.connect_sphere.venueavailability.VenueArrangementChanged(eventId,
                    List.of(bookingId), "The venue's booked setup and turnaround times for your event changed.", null));
        }
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
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId)), true);
    }

    public List<VenueBookingDto> listForVenue(UUID venueId) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return bookings.findByVenueVenueIdOrderByEventStartDatetimeAscBookingIdAsc(venueId)
                .stream().map(this::toDto).toList();
    }

    private VenueBookingDto toDto(VenueBooking booking) { return toDto(booking, false); }

    private VenueBookingDto toDto(VenueBooking booking, boolean includeRelated) {
        var event = booking.getEvent();
        var coordinator = events.findById(event.getEventId()).map(e -> e.getCoordinatorId())
                .flatMap(id -> users.findById(id)).orElse(null);
        var alternativeVenue = booking.getAlternativeVenue();
        var start = booking.getStartDatetime() == null ? event.getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? event.getEndDatetime() : booking.getEndDatetime();
        return new VenueBookingDto(booking.getBookingId(), booking.getStatus(),
                venueMapper.toDto(booking.getVenue()), new VenueBookingDto.EventRequirements(
                        event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                        event.getExpectedAttendance(), event.getVenueRequirements(),
                        List.copyOf(event.getAccessibilityNeeds()), event.getEquipmentRequirements()),
                booking.getBookingNotes(), booking.getSuitabilityNote(), booking.getSubmittedAt(), booking.getRejectReason(),
                booking.getStatus() == VenueBookingStatus.attention || availability.affected(booking.getBookingId()), booking.getReplacesBookingId(),
                booking.getAlternativeVenueId(),
                alternativeVenue == null ? null : alternativeVenue.getVenueAddress(),
                booking.getAlternativeArrangement(), availability.timing(booking.getVenue().getVenueId(), booking.getBookingId(),
                        start, end, booking.getSetupDurationMinutes(),
                        booking.getTurnaroundDurationMinutes(), booking.getTimingNote(),
                        (booking.getStatus() == VenueBookingStatus.pending || booking.getStatus() == VenueBookingStatus.approved || booking.getStatus() == VenueBookingStatus.attention)
                        && event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.completed
                        && event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.cancelled),
                holds == null ? List.of() : holds.history(booking.getBookingId()), booking.getRequirements(), effectiveRequirements(booking),
                includeRelated ? bookings.findByEventEventIdOrderByBookingIdAsc(event.getEventId()).stream()
                    .filter(b -> !b.getBookingId().equals(booking.getBookingId())).map(this::relatedBooking).toList() : List.of(),
                coordinator==null ? null : coordinator.getUsername(), coordinator==null ? null : coordinator.getEmail());
    }

    private com.example.connect_sphere.venuebooking.dto.BookingRequirements effectiveRequirements(VenueBooking booking) {
        var override = booking.getRequirements();
        var event = booking.getEvent();
        return new com.example.connect_sphere.venuebooking.dto.BookingRequirements(
                override != null && override.expectedAttendance() != null ? override.expectedAttendance() : event.getExpectedAttendance(),
                override != null && override.requiredFacilities() != null ? override.requiredFacilities() : event.getRequiredFacilities() == null ? List.of() : event.getRequiredFacilities(),
                override != null && override.accessibilityNeeds() != null ? override.accessibilityNeeds() : event.getAccessibilityNeeds(),
                override != null && override.venueRequirements() != null ? override.venueRequirements() : event.getVenueRequirements());
    }

    private VenueBookingDto.RelatedBooking relatedBooking(VenueBooking booking) {
        var start = booking.getStartDatetime() == null ? booking.getEvent().getStartDatetime() : booking.getStartDatetime();
        var end = booking.getEndDatetime() == null ? booking.getEvent().getEndDatetime() : booking.getEndDatetime();
        var occupied = com.example.connect_sphere.venueavailability.EffectivePeriod.of(start, end,
                new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(booking.getSetupDurationMinutes(), booking.getTurnaroundDurationMinutes()));
        return new VenueBookingDto.RelatedBooking(booking.getBookingId(), booking.getVenue().getVenueId(),
                booking.getVenue().getVenueAddress(), booking.getStatus(), start, end, occupied.start(), occupied.end());
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
