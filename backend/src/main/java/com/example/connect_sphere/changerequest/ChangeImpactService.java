package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.changerequest.ChangeRequestDto.ImpactItem;
import com.example.connect_sphere.equipment.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Settings;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.EffectivePeriod;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.hold.VenueHold;
import com.example.connect_sphere.venuebooking.hold.VenueHoldRepository;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.service.VenueSuitability;

/**
 * EC-NEW3: what a proposed change would do to the arrangements already made,
 * from current data, every time it is asked (never a snapshot). Each item has
 * a stable key; the coordinator must acknowledge every "attention" key to
 * approve, so an arrangement that changes in the meantime is noticed.
 *
 * The venue checks are the system's own (EC03/VS-C1/VS-C2/VS-C3/VS13), run
 * on the event as it would be. A booking stores its own window (VS-C2), so it
 * does not move by itself: one booked for the event's whole time is checked
 * at the proposed time (could it move there?) and flagged to be moved after
 * approval; one booked for part of the event (EC-C2) keeps its window and is
 * flagged to be checked. Times that only touch do not clash.
 */
@Service
public class ChangeImpactService {

    static final String OK = "ok";
    static final String ATTENTION = "attention";
    private static final Set<VenueBookingStatus> ACTIVE_BOOKINGS = Set.of(VenueBookingStatus.pending, VenueBookingStatus.approved);
    private static final DateTimeFormatter SINGAPORE = DateTimeFormatter
            .ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Singapore"));

    private final VenueBookingRecordRepository bookings;
    private final VenueRepository venues;
    private final AvailabilityService availability;
    private final VenueHoldRepository holds;
    private final EquipmentReservationService equipment;

    public ChangeImpactService(VenueBookingRecordRepository bookings, VenueRepository venues,
            AvailabilityService availability, VenueHoldRepository holds, EquipmentReservationService equipment) {
        this.bookings = bookings;
        this.venues = venues;
        this.availability = availability;
        this.holds = holds;
        this.equipment = equipment;
    }

    @Transactional(readOnly = true)
    public List<ImpactItem> assess(Event event, SaveEventRequestRequest proposed, Set<String> changedFields) {
        List<ImpactItem> items = new ArrayList<>();
        if (event == null) {
            items.add(new ImpactItem("arrangements:none", "arrangements", OK,
                    "No arrangements have been made yet",
                    "The request has not been approved, so no venue, equipment or safety arrangements depend on it."));
            return items;
        }
        boolean timesChanged = changedFields.contains("startDatetime") || changedFields.contains("endDatetime");
        Event after = eventAsProposed(event, proposed);

        List<VenueBookingRecord> active = bookings.findForEvent(event.getEventId()).stream()
                .filter(b -> ACTIVE_BOOKINGS.contains(b.getStatus())).toList();
        if (active.isEmpty()) {
            items.add(new ImpactItem("venue:none", "venue", OK, "No venue booked yet",
                    "There is no venue booking for this change to affect."));
        }
        for (VenueBookingRecord booking : active) {
            venueImpact(event, after, booking, timesChanged, items);
        }
        equipmentImpact(event, proposed, changedFields, timesChanged, items);
        safetyImpact(event, changedFields, items);
        return items;
    }

    private void venueImpact(Event event, Event after, VenueBookingRecord booking, boolean timesChanged,
            List<ImpactItem> items) {
        Venue venue = venues.findById(booking.getVenueId()).orElse(null);
        if (venue == null) {
            return;
        }
        String name = venue.getVenueAddress()
                + (booking.getStatus() == VenueBookingStatus.pending ? " (requested, not yet approved)" : "");
        String key = "venue:" + booking.getBookingId() + ":";
        // Booked for the event's whole time (the usual case, or a legacy row
        // without its own window): it should move with the event.
        OffsetDateTime bookedStart = booking.getStartDatetime() != null ? booking.getStartDatetime() : event.getStartDatetime();
        OffsetDateTime bookedEnd = booking.getEndDatetime() != null ? booking.getEndDatetime() : event.getEndDatetime();
        boolean followsEvent = bookedStart.toInstant().equals(event.getStartDatetime().toInstant())
                && bookedEnd.toInstant().equals(event.getEndDatetime().toInstant());
        OffsetDateTime start = followsEvent ? after.getStartDatetime() : bookedStart;
        OffsetDateTime end = followsEvent ? after.getEndDatetime() : bookedEnd;
        Settings durations = new Settings(booking.getSetupDurationMinutes(), booking.getTurnaroundDurationMinutes());
        EffectivePeriod occupied = EffectivePeriod.of(start, end, durations);
        int before = items.size();

        if (timesChanged && followsEvent) {
            items.add(new ImpactItem(key + "move", "venue", ATTENTION, name + " is booked for the old time",
                    "Booked " + period(bookedStart, bookedEnd) + ". After approval, change the booking to "
                            + period(start, end) + " or book another venue. Nothing is moved automatically."));
        } else if (timesChanged) {
            items.add(new ImpactItem(key + "own-times", "venue", ATTENTION, name + " keeps its own times",
                    "It is booked " + period(start, end) + " for part of the event and does not move with it. "
                            + "Check it still suits the new schedule."));
        }
        var clashes = availability.conflictsForBooking(venue.getVenueId(), booking.getBookingId(), start, end, durations)
                .stream().filter(c -> !event.getEventId().equals(c.eventId())).toList();
        if (!clashes.isEmpty()) {
            // Another event's name is never shown to a coordinator (ECL-C7).
            items.add(new ImpactItem(key + "clash", "venue", ATTENTION, name + " is booked by another event then",
                    String.join("; ", clashes.stream().map(c -> "Booked by another event, "
                            + period(c.effectiveStart(), c.effectiveEnd())).toList())
                            + ". This event would occupy it " + period(occupied.start(), occupied.end())
                            + ", including setup and turnaround."));
        }
        var unavailable = availability.unavailablePeriods(venue.getVenueId(), start, end, durations);
        if (!unavailable.isEmpty()) {
            items.add(new ImpactItem(key + "unavailable", "venue", ATTENTION, name + " is unavailable then",
                    String.join("; ", unavailable.stream().map(p -> period(p.startDateTime(), p.endDateTime())
                            + ": " + p.reason()).toList()) + "."));
        }
        if (availability.hasOperationalIssue(venue.getVenueId(), start, end, durations)) {
            items.add(new ImpactItem(key + "issue", "venue", ATTENTION, name + " has an operational issue then",
                    "An operational issue is recorded at the venue during " + period(occupied.start(), occupied.end()) + "."));
        }
        if (availability.outsideOperatingHours(venue.getVenueId(), start, end, durations)) {
            items.add(new ImpactItem(key + "hours", "venue", ATTENTION, name + " is closed for part of it",
                    "Occupied " + period(occupied.start(), occupied.end())
                            + " including setup and turnaround, which is outside the venue's opening hours on a single day."));
        }
        VenueSuitability fit = VenueSuitability.of(after, venue);
        if (!fit.capacityOk()) {
            items.add(new ImpactItem(key + "capacity", "venue", ATTENTION, name + " is too small",
                    "It holds " + fit.capacity() + "; " + fit.expectedAttendance() + " are expected."));
        }
        if (!fit.missingAccessibility().isEmpty()) {
            items.add(new ImpactItem(key + "accessibility", "venue", ATTENTION, name + " lacks accessibility needed",
                    "Missing: " + fit.missingAccessibilityLabels() + "."));
        }
        if (!fit.facilitiesOk()) {
            items.add(new ImpactItem(key + "facilities", "venue", ATTENTION, name + " lacks required facilities",
                    "Missing: " + fit.missingFacilityLabels() + "."));
        }
        for (VenueHold hold : holds.history(booking.getBookingId())) {
            if ("active".equals(hold.status()) && (hold.reservedStart().isAfter(occupied.start())
                    || hold.reservedEnd().isBefore(occupied.end()))) {
                items.add(new ImpactItem(key + "hold", "venue", ATTENTION, name + "'s tentative hold no longer covers it",
                        "The hold reserves " + period(hold.reservedStart(), hold.reservedEnd())
                                + "; the event would need " + period(occupied.start(), occupied.end()) + "."));
            }
        }
        if (items.size() == before) {
            items.add(new ImpactItem(key + "ok", "venue", OK, name + " still fits",
                    "Free, open and suitable " + period(occupied.start(), occupied.end())
                            + ", including setup and turnaround."));
        }
    }

    private void equipmentImpact(Event event, SaveEventRequestRequest proposed, Set<String> changedFields,
            boolean timesChanged, List<ImpactItem> items) {
        if (timesChanged) {
            for (EquipmentReservationResponse r : equipment.reservationsForEvent(event.getEventId())) {
                boolean covers = r.loanedFrom() != null && r.loanedUntil() != null
                        && !r.loanedFrom().isAfter(proposed.startDatetime().toInstant())
                        && !r.loanedUntil().isBefore(proposed.endDatetime().toInstant());
                if (!covers) {
                    items.add(new ImpactItem("equipment:" + r.logId(), "equipment", ATTENTION,
                            r.equipmentName() + " is reserved for the old time",
                            r.quantity() + " reserved " + period(r.loanedFrom(), r.loanedUntil())
                                    + ". Technical Support need to move the reservation."));
                }
            }
        }
        if (changedFields.contains("equipmentRequirements")) {
            items.add(new ImpactItem("equipment:requirements", "equipment", ATTENTION, "Equipment requirements change",
                    "Update the event's equipment request to match (EC07)."));
        }
    }

    private void safetyImpact(Event event, Set<String> changedFields, List<ImpactItem> items) {
        if (!EventStatus.IN_OR_PAST_SAFETY_CHECK.contains(event.getStatus())) {
            return;
        }
        boolean replan = changedFields.stream().anyMatch(ChangeFields.AFFECTS_ARRANGEMENTS::contains);
        items.add(replan
                ? new ImpactItem("safety:replan", "safety", ATTENTION, "The event goes back to Planning",
                        "Approving undoes its safety approval or confirmation; it must pass the safety check "
                                + "and be confirmed again (EC08).")
                : new ImpactItem("safety:kept", "safety", OK, "Safety approval and confirmation are kept",
                        "Only details that do not affect arrangements change."));
    }

    /** The event as it would be, for the system's own suitability rules. */
    private static Event eventAsProposed(Event event, SaveEventRequestRequest p) {
        Event after = new Event();
        after.setEventId(event.getEventId());
        after.setStartDatetime(p.startDatetime());
        after.setEndDatetime(p.endDatetime());
        after.setExpectedAttendance(p.expectedAttendance());
        after.setAccessibilityNeeds(p.accessibilityNeeds() == null ? List.of()
                : p.accessibilityNeeds().stream().map(Enum::name).toList());
        after.setRequiredFacilities(p.requiredFacilities() == null ? List.of()
                : p.requiredFacilities().stream().map(Enum::name).toList());
        return after;
    }

    private static String period(OffsetDateTime start, OffsetDateTime end) {
        return SINGAPORE.format(start) + " – " + SINGAPORE.format(end) + " (Singapore time)";
    }

    private static String period(java.time.Instant start, java.time.Instant end) {
        return start == null || end == null ? "at no recorded time" : SINGAPORE.format(start) + " – " + SINGAPORE.format(end)
                + " (Singapore time)";
    }

    static List<String> attentionKeys(List<ImpactItem> items) {
        return items.stream().filter(i -> ATTENTION.equals(i.status())).map(ImpactItem::key).toList();
    }
}
