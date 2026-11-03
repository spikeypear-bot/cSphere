package com.example.connect_sphere.changerequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.changerequest.dto.ChangeRequestDto.ImpactItem;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.equipment.dto.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.service.EquipmentReservationService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.service.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.hold.VenueHold;
import com.example.connect_sphere.venuebooking.hold.VenueHoldRepository;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EC-NEW3's impact rules on small, exact fixtures. Availability (clashes,
 * hours, unavailability) is a stub answering "free"; its own boundaries are
 * proven in ChangeRequestFlowTest against PostgreSQL. Here: capacity per
 * venue, accessibility, holds, equipment windows, part-day bookings and the
 * safety consequence.
 */
@Tag("unit")
@Tag("EC-NEW3")
class ChangeImpactServiceTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2027-03-10T12:00:00+08:00");

    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final AvailabilityService availability = mock(AvailabilityService.class);
    private final VenueHoldRepository holds = mock(VenueHoldRepository.class);
    private final EquipmentReservationService equipment = mock(EquipmentReservationService.class);
    private final ChangeImpactService service = new ChangeImpactService(bookings, venues, availability, holds, equipment);
    private final Event event = new Event();

    @BeforeEach
    void setUp() {
        event.setEventId(UUID.randomUUID());
        event.setStartDatetime(START);
        event.setEndDatetime(END);
        event.setExpectedAttendance(150);
        event.setStatus(EventStatus.pending);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of());
        when(availability.conflictsForBooking(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(availability.unavailablePeriods(any(), any(), any(), any())).thenReturn(List.of());
        when(holds.history(any())).thenReturn(List.of());
        when(equipment.reservationsForEvent(any())).thenReturn(List.of());
    }

    private VenueBookingRecord booked(String name, int capacity, List<String> accessibility,
            OffsetDateTime from, OffsetDateTime to) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(name);
        venue.setVenueCapacity(capacity);
        venue.setVenueAccessibilities(accessibility);
        when(venues.findById(venue.getVenueId())).thenReturn(Optional.of(venue));
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setVenueId(venue.getVenueId());
        booking.setEventId(event.getEventId());
        booking.setStatus(VenueBookingStatus.approved);
        booking.setStartDatetime(from);
        booking.setEndDatetime(to);
        return booking;
    }

    private static SaveEventRequestRequest proposal(OffsetDateTime start, OffsetDateTime end, int attendance,
            List<AccessibilityFeature> accessibility, String equipment) {
        return new SaveEventRequestRequest("Town Hall", "Update", null, start, end, attendance, "Theatre",
                equipment, accessibility, List.of(), false);
    }

    private static List<String> attention(List<ImpactItem> items) {
        return ChangeImpactService.attentionKeys(items);
    }

    @Test
    @DisplayName("EC-NEW3 AC6 (happy): a request not yet approved has no arrangements to affect")
    void noEventNoArrangements() {
        List<ImpactItem> items = service.assess(null, proposal(START, END, 150, List.of(), null), Set.of("expectedAttendance"));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("arrangements:none");
            assertThat(i.status()).isEqualTo("ok");
        });
    }

    @Test
    @DisplayName("EC-NEW3 AC17, AC18 (boundary): capacity is judged per venue; two 100-seat rooms do not hold 150, even though together they could")
    void capacityIsNotSummed() {
        VenueBookingRecord a = booked("Room A", 100, List.of(), START, END);
        VenueBookingRecord b = booked("Room B", 100, List.of(), START, END);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(a, b));

        List<String> keys = attention(service.assess(event, proposal(START, END, 150, List.of(), null), Set.of("expectedAttendance")));

        assertThat(keys).contains("venue:" + a.getBookingId() + ":capacity", "venue:" + b.getBookingId() + ":capacity");
        assertThat(attention(service.assess(event, proposal(START, END, 100, List.of(), null), Set.of("expectedAttendance"))))
                .isEmpty();
    }

    @Test
    @DisplayName("EC-NEW3 AC19 (negative): a newly needed accessibility feature the venue lacks needs attention")
    void accessibilityTheVenueLacks() {
        VenueBookingRecord hall = booked("Hall", 300, List.of("step_free_access"), START, END);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));

        List<ImpactItem> items = service.assess(event, proposal(START, END, 150,
                List.of(AccessibilityFeature.step_free_access, AccessibilityFeature.elevators), null), Set.of("accessibilityNeeds"));

        assertThat(attention(items)).containsExactly("venue:" + hall.getBookingId() + ":accessibility");
    }

    @Test
    @DisplayName("EC-NEW3 AC8, AC32 (happy): a booking for the whole event is checked at the new time and flagged to be moved; nothing moves by itself")
    void aWholeEventBookingIsFlaggedToMove() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));

        List<ImpactItem> items = service.assess(event, proposal(START.plusHours(1), END.plusHours(1), 150, List.of(), null),
                Set.of("startDatetime", "endDatetime"));

        assertThat(attention(items)).containsExactly("venue:" + hall.getBookingId() + ":move");
        assertThat(items.get(0).detail()).contains("Nothing is moved automatically");
        assertThat(hall.getStartDatetime()).isEqualTo(START);
    }

    @Test
    @DisplayName("EC-NEW3 AC8 (boundary): a breakout room booked for part of the event (EC-C2) keeps its own window and is flagged to be checked")
    void aPartDayBookingKeepsItsWindow() {
        VenueBookingRecord breakout = booked("Breakout", 50, List.of(), START.plusHours(1), START.plusHours(2));
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(breakout));

        List<String> keys = attention(service.assess(event, proposal(START.plusHours(1), END.plusHours(1), 40, List.of(), null),
                Set.of("startDatetime", "endDatetime", "expectedAttendance")));

        assertThat(keys).containsExactly("venue:" + breakout.getBookingId() + ":own-times");
    }

    @Test
    @DisplayName("EC-NEW3 AC16 (boundary): a tentative hold that no longer covers the event's occupied time needs attention")
    void aHoldThatNoLongerCovers() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        hall.setStatus(VenueBookingStatus.pending);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));
        when(holds.history(hall.getBookingId())).thenReturn(List.of(new VenueHold(UUID.randomUUID(), hall.getBookingId(),
                hall.getVenueId(), event.getEventId(), "Town Hall", START, END, END.plusDays(3), null, "active", UUID.randomUUID(),
                START.minusDays(1), null, START.minusDays(1))));

        List<ImpactItem> items = service.assess(event, proposal(START, END.plusMinutes(1), 150, List.of(), null),
                Set.of("endDatetime"));

        assertThat(attention(items)).contains("venue:" + hall.getBookingId() + ":hold");
        assertThat(items.get(0).title()).contains("(requested, not yet approved)");
        // Exactly the held time still fits.
        assertThat(attention(service.assess(event, proposal(START.plusMinutes(1), END, 150, List.of(), null),
                Set.of("startDatetime")))).doesNotContain("venue:" + hall.getBookingId() + ":hold");
    }

    @Test
    @DisplayName("EC-NEW3 AC20, AC21 (negative): equipment reserved for the old time, and new equipment requirements, need attention")
    void equipmentWindowsAndRequirements() {
        UUID log = UUID.randomUUID();
        when(equipment.reservationsForEvent(event.getEventId())).thenReturn(List.of(new EquipmentReservationResponse(
                log, event.getEventId(), UUID.randomUUID(), "Wireless mic", 4, null, START.toInstant(), END.toInstant(), null, false)));

        List<String> moved = attention(service.assess(event, proposal(START, END.plusMinutes(30), 150, List.of(), null),
                Set.of("endDatetime")));
        List<String> same = attention(service.assess(event, proposal(START.plusMinutes(30), END, 150, List.of(), null),
                Set.of("startDatetime")));
        List<String> more = attention(service.assess(event, proposal(START, END, 150, List.of(), "6 mics"),
                Set.of("equipmentRequirements")));

        assertThat(moved).containsExactly("equipment:" + log);
        assertThat(same).isEmpty();
        assertThat(more).containsExactly("equipment:requirements");
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (negative): equipment the event asks for that is not free at the new time needs attention; enough is fine")
    void equipmentAvailabilityAtTheNewTime() {
        UUID projector = UUID.randomUUID();
        UUID mic = UUID.randomUUID();
        var later = START.plusDays(1);
        when(equipment.needsAt(event.getEventId(), later.toInstant(), END.plusDays(1).toInstant())).thenReturn(List.of(
                new EquipmentReservationService.NeedAt(projector, "Projector", 2, 1),
                new EquipmentReservationService.NeedAt(mic, "Wireless mic", 4, 4)));

        List<ImpactItem> items = service.assess(event, proposal(later, END.plusDays(1), 150, List.of(), null),
                Set.of("startDatetime", "endDatetime"));

        assertThat(attention(items)).containsExactly("equipment:available:" + projector);
        assertThat(items).filteredOn(i -> i.key().equals("equipment:available:" + projector)).singleElement()
                .satisfies(i -> assertThat(i.detail()).startsWith("Only 1 of the 2 needed is free"));
        assertThat(items).filteredOn(i -> i.key().equals("equipment:available:" + mic)).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo(ChangeImpactService.OK));
    }

    @Test
    @DisplayName("EC-NEW3 (D40) (negative): asking for more of an item than is free at the event's time needs attention; exactly enough is fine")
    void proposedEquipmentQuantitiesAgainstFreeStock() {
        UUID projector = UUID.randomUUID();
        UUID mic = UUID.randomUUID();
        var proposed = new SaveEventRequestRequest("Town Hall", "Update", null, START, END, 150, "Theatre", null,
                List.of(), List.of(), false, null, null, null, null,
                List.of(new com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest.Item(projector, 5),
                        new com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest.Item(mic, 4)));
        when(equipment.needsAt(event.getEventId(), java.util.Map.of(projector, 5, mic, 4), START.toInstant(), END.toInstant()))
                .thenReturn(List.of(new EquipmentReservationService.NeedAt(projector, "Projector", 5, 3),
                        new EquipmentReservationService.NeedAt(mic, "Wireless mic", 4, 4)));

        List<ImpactItem> items = service.assess(event, proposed, Set.of("equipmentItems"));

        assertThat(attention(items)).containsExactlyInAnyOrder("equipment:items:" + projector, "equipment:requirements");
        assertThat(items).filteredOn(i -> i.key().equals("equipment:items:" + projector)).singleElement()
                .satisfies(i -> assertThat(i.detail()).startsWith("Only 3 of the 5 asked for is free"));
        assertThat(items).filteredOn(i -> i.key().equals("equipment:items:" + mic)).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo(ChangeImpactService.OK));
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (boundary): equipment availability is not checked when the times do not change")
    void equipmentAvailabilityIsNotCheckedWithoutATimeChange() {
        service.assess(event, proposal(START, END, 150, List.of(), "6 mics"), Set.of("equipmentRequirements"));

        org.mockito.Mockito.verify(equipment, org.mockito.Mockito.never()).needsAt(any(), any(), any());
    }

    @Test
    @DisplayName("EC-NEW3 AC23, AC35 (happy): in the safety stages an important change sends the event back to Planning; a name change keeps it")
    void safetyConsequence() {
        event.setStatus(EventStatus.confirmed);

        assertThat(attention(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance"))))
                .containsExactly("safety:replan");
        assertThat(service.assess(event, proposal(START, END, 150, List.of(), null), Set.of("eventName")))
                .anySatisfy(i -> assertThat(i.key()).isEqualTo("safety:kept"))
                .allSatisfy(i -> assertThat(i.status()).isEqualTo("ok"));
        event.setStatus(EventStatus.pending);
        assertThat(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance")))
                .noneSatisfy(i -> assertThat(i.area()).isEqualTo("safety"));
    }

    @Test
    @DisplayName("EC-NEW3 (boundary): a hold that starts after the new start needs attention; an ended hold is not checked")
    void holdsStartingLateOrEnded() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));
        VenueHold active = new VenueHold(UUID.randomUUID(), hall.getBookingId(), hall.getVenueId(), event.getEventId(), "Town Hall",
                START, END, END.plusDays(3), null, "active", UUID.randomUUID(), START.minusDays(1), null, START.minusDays(1));
        VenueHold ended = new VenueHold(UUID.randomUUID(), hall.getBookingId(), hall.getVenueId(), event.getEventId(), "Town Hall",
                START, START.plusMinutes(5), END.plusDays(3), null, "released", UUID.randomUUID(), START.minusDays(1), START, START);
        when(holds.history(hall.getBookingId())).thenReturn(List.of(active, ended));

        List<String> earlier = attention(service.assess(event, proposal(START.minusMinutes(1), END, 150, List.of(), null),
                Set.of("startDatetime")));

        assertThat(earlier).contains("venue:" + hall.getBookingId() + ":hold");
        when(holds.history(hall.getBookingId())).thenReturn(List.of(ended));
        assertThat(attention(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance"))))
                .isEmpty();
    }

    @Test
    @DisplayName("EC-NEW3 (negative): an operational issue at the venue then needs attention, in the impact and in the staff check list")
    void anOperationalIssueNeedsAttention() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        venues.findById(hall.getVenueId()).orElseThrow().setSupportedLayouts(List.of("theatre"));
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));
        when(availability.hasOperationalIssue(any(), any(), any(), any())).thenReturn(true);
        event.setAccessibilityNeeds(List.of());
        event.setRequiredFacilities(List.of());

        List<String> keys = attention(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance")));
        List<ImpactItem> checks = service.assessBooking(event, hall);
        var summary = service.bookingSummary(event, hall, checks);

        assertThat(keys).containsExactly("venue:" + hall.getBookingId() + ":issue");
        assertThat(summary.checks()).filteredOn(i -> i.key().equals("issue")).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo(ChangeImpactService.ATTENTION));
    }

    @Test
    @DisplayName("EC-NEW3 (boundary): a booking reserved from before the event's current start is flagged in the staff check list; a legacy booking without its own times uses the event's")
    void reservedTimesOutsideTheSchedule() {
        VenueBookingRecord early = booked("Hall", 300, List.of(), START.minusHours(1), END);
        VenueBookingRecord legacy = booked("Annex", 300, List.of(), null, null);
        for (VenueBookingRecord b : List.of(early, legacy)) {
            venues.findById(b.getVenueId()).orElseThrow().setSupportedLayouts(List.of("theatre"));
        }
        event.setAccessibilityNeeds(List.of());
        event.setRequiredFacilities(List.of());

        var earlySummary = service.bookingSummary(event, early, service.assessBooking(event, early));
        List<ImpactItem> legacyChecks = service.assessBooking(event, legacy);
        var legacySummary = service.bookingSummary(event, legacy, legacyChecks);

        assertThat(earlySummary.checks()).filteredOn(i -> i.key().equals("schedule")).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo(ChangeImpactService.ATTENTION));
        assertThat(legacyChecks).noneMatch(i -> i.key().endsWith(":schedule"));
        assertThat(legacySummary.checks()).noneMatch(i -> i.key().equals("schedule"));
        assertThat(legacySummary.occupiedStart().toInstant()).isEqualTo(START.toInstant());
    }

    private com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Booking conflict(UUID eventId, String status) {
        return new com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Booking(UUID.randomUUID(), eventId,
                UUID.randomUUID(), "Other", status, START, END, START, END, UUID.randomUUID(), "Hall", START, END);
    }

    @Test
    @DisplayName("EC-NEW3 (conflict): another event's tentative hold on the venue shows as a conflicting hold in the staff check list; the event's own bookings never clash with it")
    void holdsAndOwnBookingsInTheChecks() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        venues.findById(hall.getVenueId()).orElseThrow().setSupportedLayouts(List.of("theatre"));
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));
        event.setAccessibilityNeeds(List.of());
        event.setRequiredFacilities(List.of());
        when(availability.conflictsForBooking(any(), any(), any(), any(), any()))
                .thenReturn(List.of(conflict(event.getEventId(), "approved")));

        assertThat(attention(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance"))))
                .isEmpty();

        when(availability.conflictsForBooking(any(), any(), any(), any(), any()))
                .thenReturn(List.of(conflict(UUID.randomUUID(), "hold")));
        var summary = service.bookingSummary(event, hall, List.of());
        assertThat(summary.checks()).filteredOn(i -> i.key().equals("hold")).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(ChangeImpactService.ATTENTION);
            assertThat(i.detail()).startsWith("Conflicting hold: ");
        });
        when(availability.conflictsForBooking(any(), any(), any(), any(), any())).thenReturn(List.of());
        var uncovered = new ImpactItem("venue:" + hall.getBookingId() + ":hold", "venue", ChangeImpactService.ATTENTION,
                "Hall's tentative hold no longer covers it", "The hold reserves less than the event needs.");
        assertThat(service.bookingSummary(event, hall, List.of(uncovered)).checks()).filteredOn(i -> i.key().equals("hold"))
                .singleElement().satisfies(i -> assertThat(i.detail()).isEqualTo("The hold reserves less than the event needs."));
    }

    @Test
    @DisplayName("EC-NEW3 AC7 (happy): a venue that still fits says so, so the coordinator sees what was checked")
    void aFittingVenueSaysSo() {
        VenueBookingRecord hall = booked("Hall", 300, List.of(), START, END);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(hall));

        assertThat(service.assess(event, proposal(START, END, 160, List.of(), null), Set.of("expectedAttendance")))
                .singleElement().satisfies(i -> {
                    assertThat(i.key()).isEqualTo("venue:" + hall.getBookingId() + ":ok");
                    assertThat(i.title()).isEqualTo("Hall still fits");
                    assertThat(i.detail()).contains("(Singapore time)");
                });
    }
}
