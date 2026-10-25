package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.dto.OrganiserArrangementsDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EO04: what an Event Organiser may see of their event's arrangements. Every
 * repository is a stub (canned answers) and the clock is fixed, so each test
 * checks only the service's own rules: whose event it is, when arrangements
 * are shown, and what is never shown. Display names cite the EO04 AC (as
 * numbered on Sprint Backlog 3) and the kind of case, so each test traces to
 * its requirement (IS212 Week 4/9).
 */
@Tag("unit")
@Tag("EO04")
@Tag("VS17")
class EventArrangementsServiceTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final String ACME = "Acme Pte Ltd";
    private static final UUID COORDINATOR = UUID.randomUUID();

    // Stubs: each returns canned data for the event under test.
    private final EventRepository events = mock(EventRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final EquipmentRequestRepository equipmentRequests = mock(EquipmentRequestRepository.class);
    private final EquipmentRequestLineRepository equipmentLines = mock(EquipmentRequestLineRepository.class);
    private final AvailabilityService availability = mock(AvailabilityService.class);
    private final UserRepository users = mock(UserRepository.class);

    private final List<VenueBookingRecord> eventBookings = new ArrayList<>();
    private final List<EquipmentRequest> eventEquipment = new ArrayList<>();
    private EventArrangementsService service;

    @BeforeEach
    void setUp() {
        service = new EventArrangementsService(events, bookings, venues, equipmentRequests, equipmentLines,
                availability, users, new JourneyStatusResolver(events, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(bookings.findForEvent(any())).thenReturn(eventBookings);
        when(equipmentRequests.findByEventId(any())).thenReturn(eventEquipment);
        User coordinator = new User();
        coordinator.setUserId(COORDINATOR);
        coordinator.setUsername("ec1");
        coordinator.setEmail("ec1@connectsphere.test");
        when(users.findById(COORDINATOR)).thenReturn(Optional.of(coordinator));
    }

    // ---- whose event (AC1–AC2) --------------------------------------------

    @Test
    @DisplayName("EO04 AC1 (happy): any organiser of the owning organisation can view it, not only its submitter")
    void anOrganiserOfTheSameOrganisationCanViewIt() {
        Event event = event(EventStatus.confirmed);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.eventId()).isEqualTo(event.getEventId());
        assertThat(view.view()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("EO04 AC2 (negative): another organisation's event is reported as not found, revealing nothing")
    void anotherOrganisationsEventIsNotFound() {
        Event event = event(EventStatus.confirmed);
        approvedVenue("Marina Hall");

        assertThatThrownBy(() -> service.forOrganiser("Globex", event.getEventId()))
                .isInstanceOf(EventNotFoundException.class);
        // Nothing about the event's arrangements is even read.
        verify(bookings, never()).findForEvent(any());
    }

    @Test
    @DisplayName("EO04 AC2 (boundary): a token with no organisation claim sees nothing")
    void aMissingOrganisationClaimSeesNothing() {
        Event event = event(EventStatus.confirmed);

        assertThatThrownBy(() -> service.forOrganiser(null, event.getEventId()))
                .isInstanceOf(EventNotFoundException.class);
    }

    // ---- before confirmation (AC6–AC8) ------------------------------------

    @Test
    @DisplayName("EO04 AC6, AC7, AC8 (happy): before confirmation, status and coordinator are shown but no venue or equipment")
    void beforeConfirmationOnlyStatusAndCoordinatorAreShown() {
        Event event = event(EventStatus.safety_approved);
        approvedVenue("Marina Hall");
        approvedEquipment("Wireless microphone", 2);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.view()).isEqualTo("not_confirmed");
        assertThat(view.journeyStatus().label()).isEqualTo("Safety Approved");
        assertThat(view.journeyStatus().explanation()).isNotBlank();
        assertThat(view.coordinatorName()).isEqualTo("ec1");
        assertThat(view.coordinatorEmail()).isEqualTo("ec1@connectsphere.test");
        assertThat(view.venues()).isEmpty();
        assertThat(view.equipment()).isEmpty();
        assertThat(view.safetyApprovedAt()).isNull();
    }

    // ---- confirmed arrangements (AC9, AC12–AC17) ---------------------------

    @Test
    @DisplayName("EO04 AC9, AC12, AC16, AC17 (happy): a confirmed event shows its timing, venue details, coordinator and safety approval")
    void aConfirmedEventShowsItsArrangements() {
        Event event = event(EventStatus.confirmed);
        Venue hall = approvedVenue("Marina Hall");

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.eventName()).isEqualTo("Town Hall");
        assertThat(view.startDatetime()).isEqualTo(event.getStartDatetime());
        assertThat(view.endDatetime()).isEqualTo(event.getEndDatetime());
        assertThat(view.expectedAttendance()).isEqualTo(150);
        assertThat(view.venues()).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("Marina Hall");
            assertThat(v.capacity()).isEqualTo(200);
            assertThat(v.layouts()).containsExactly("theatre");
            assertThat(v.accessibilities()).containsExactly("step_free_access");
            assertThat(v.facilities()).containsExactly("projection");
            assertThat(v.operatingInformation()).isEqualTo("Operating hours not configured");
            assertThat(v.operatingHours()).isEmpty();
            assertThat(v.venueId()).isEqualTo(hall.getVenueId());
            assertThat(v.noLongerAvailable()).isFalse();
        });
        assertThat(view.coordinatorEmail()).isEqualTo("ec1@connectsphere.test");
        assertThat(view.safetyApprovedAt()).isEqualTo(event.getSafetyApprovedAt());
    }

    @Test
    @DisplayName("EO04 AC12 (happy): every approved venue of a multi-venue event is listed (EC-C2)")
    void everyApprovedVenueOfAMultiVenueEventIsListed() {
        Event event = event(EventStatus.confirmed);
        approvedVenue("Marina Hall");
        approvedVenue("Harbour Room");

        assertThat(service.forOrganiser(ACME, event.getEventId()).venues())
                .extracting(OrganiserArrangementsDto.Venue::name)
                .containsExactly("Marina Hall", "Harbour Room");
    }

    @Test
    @DisplayName("EO04 AC13 (happy): approved equipment is listed by item, with quantities merged across requests")
    void approvedEquipmentIsMergedByItem() {
        Event event = event(EventStatus.confirmed);
        approvedEquipment("Wireless microphone", 2);
        approvedEquipment("Wireless microphone", 3);
        approvedEquipment("Projector", 1);

        assertThat(service.forOrganiser(ACME, event.getEventId()).equipment())
                .containsExactly(new OrganiserArrangementsDto.Equipment("Wireless microphone", 5),
                        new OrganiserArrangementsDto.Equipment("Projector", 1));
    }

    @Test
    @DisplayName("EO04 AC14 (boundary): an event with no approved equipment returns an empty list for the page to explain")
    void noEquipmentIsAnEmptyList() {
        Event event = event(EventStatus.confirmed);

        assertThat(service.forOrganiser(ACME, event.getEventId()).equipment()).isEmpty();
    }

    @Test
    @DisplayName("EO04 AC15 (happy): the organiser's stated accessibility needs come back to compare with each venue")
    void statedAccessibilityNeedsAreReturned() {
        Event event = event(EventStatus.confirmed);
        event.setAccessibilityNeeds(new ArrayList<>(List.of("step_free_access", "elevators")));

        assertThat(service.forOrganiser(ACME, event.getEventId()).accessibilityNeeds())
                .containsExactly("step_free_access", "elevators");
    }

    // ---- never shown (AC18–AC20) -------------------------------------------

    @Test
    @DisplayName("EO04 AC19 (negative): rejected, cancelled and pending venue requests are never shown")
    void onlyApprovedBookingsAreShown() {
        Event event = event(EventStatus.confirmed);
        approvedVenue("Marina Hall");
        booking(VenueBookingStatus.rejected, venue("Rejected Room"));
        booking(VenueBookingStatus.cancelled, venue("Cancelled Room"));
        booking(VenueBookingStatus.pending, venue("Pending Room"));

        assertThat(service.forOrganiser(ACME, event.getEventId()).venues())
                .extracting(OrganiserArrangementsDto.Venue::name).containsExactly("Marina Hall");
    }

    @Test
    @DisplayName("EO04 AC19 (negative): equipment requests not yet approved are never shown")
    void onlyApprovedEquipmentIsShown() {
        Event event = event(EventStatus.confirmed);
        approvedEquipment("Projector", 1);
        equipment(EquipmentRequestStatus.processing, "Smoke machine", 1);
        equipment(EquipmentRequestStatus.rejected, "Laser", 1);

        assertThat(service.forOrganiser(ACME, event.getEventId()).equipment())
                .extracting(OrganiserArrangementsDto.Equipment::name).containsExactly("Projector");
    }

    @Test
    @DisplayName("EO04 AC18, AC20 (negative): the response has no field for notes, justifications, holds, buffers, checklist or timeline")
    void theResponseCannotCarryInternalDetails() {
        // Checked on the record's shape, not one response: a field that does
        // not exist cannot leak, whatever data an event happens to hold.
        List<String> fields = Arrays.stream(OrganiserArrangementsDto.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase()).toList();
        List<String> venueFields = Arrays.stream(OrganiserArrangementsDto.Venue.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase()).toList();
        for (String forbidden : List.of("note", "justification", "hold", "setup", "turnaround", "buffer",
                "checklist", "timeline", "feedback", "reason")) {
            assertThat(fields).noneMatch(f -> f.contains(forbidden));
            assertThat(venueFields).noneMatch(f -> f.contains(forbidden));
        }
    }

    // ---- after confirmation (AC21–AC25) ------------------------------------

    @Test
    @DisplayName("EO04 AC21, AC22 (conflict): a venue that became unavailable after confirmation is flagged while the rest stays visible")
    void aVenueThatBecameUnavailableIsFlagged() {
        Event event = event(EventStatus.pending);
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        VenueBookingRecord affected = approvedVenueBooking("Marina Hall");
        approvedVenue("Harbour Room");
        when(availability.affected(affected.getBookingId())).thenReturn(true);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.view()).isEqualTo("reconfirming");
        assertThat(view.venues()).extracting(OrganiserArrangementsDto.Venue::name, OrganiserArrangementsDto.Venue::noLongerAvailable)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Marina Hall", true),
                        org.assertj.core.groups.Tuple.tuple("Harbour Room", false));
    }

    @Test
    @DisplayName("EO04 AC23 (happy): first and latest confirmation dates are both returned for the Updated marker")
    void bothConfirmationDatesAreReturned() {
        Event event = event(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        event.setConfirmedAt(OffsetDateTime.parse("2026-12-05T00:00:00Z"));

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.firstConfirmedAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        assertThat(view.confirmedAt()).isEqualTo(OffsetDateTime.parse("2026-12-05T00:00:00Z"));
    }

    @Test
    @DisplayName("EO04 AC24 (failure): a cancelled event shows no arrangements as if they still applied")
    void aCancelledEventShowsNoArrangements() {
        Event event = event(EventStatus.cancelled);
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        approvedVenue("Marina Hall");
        approvedEquipment("Projector", 1);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.view()).isEqualTo("cancelled");
        assertThat(view.venues()).isEmpty();
        assertThat(view.equipment()).isEmpty();
        assertThat(view.safetyApprovedAt()).isNull();
    }

    @Test
    @DisplayName("EO04 AC25 (boundary): a confirmed event whose end has passed is kept as a Completed record")
    void aPastConfirmedEventIsACompletedRecord() {
        Event event = event(EventStatus.confirmed);
        event.setStartDatetime(OffsetDateTime.parse("2026-12-01T01:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2026-12-01T04:00:00Z"));
        approvedVenue("Marina Hall");

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.view()).isEqualTo("completed");
        assertThat(view.journeyStatus().label()).isEqualTo("Completed");
        assertThat(view.venues()).hasSize(1);
    }

    @Test
    @DisplayName("EO04 AC7 (boundary): an event with no coordinator yet has no contact rather than failing")
    void noCoordinatorMeansNoContact() {
        Event event = event(EventStatus.pending);
        event.setCoordinatorId(null);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.coordinatorName()).isNull();
        assertThat(view.coordinatorEmail()).isNull();
    }

    @Test
    @DisplayName("EO04 AC12 (failure): a booking whose venue record is missing is skipped, not a crash")
    void aBookingWithAMissingVenueIsSkipped() {
        Event event = event(EventStatus.confirmed);
        VenueBookingRecord orphan = booking(VenueBookingStatus.approved, null);
        when(venues.findById(orphan.getVenueId())).thenReturn(Optional.empty());

        assertThat(service.forOrganiser(ACME, event.getEventId()).venues()).isEmpty();
    }

    @Test
    @DisplayName("EO04 AC12, AC15 (boundary): records saved before these lists existed come back as empty lists, not errors")
    void missingListsComeBackEmpty() {
        Event event = event(EventStatus.confirmed);
        event.setAccessibilityNeeds(null);
        Venue hall = approvedVenue("Marina Hall");
        hall.setSupportedLayouts(null);
        hall.setVenueFacilities(null);

        OrganiserArrangementsDto view = service.forOrganiser(ACME, event.getEventId());

        assertThat(view.accessibilityNeeds()).isEmpty();
        assertThat(view.venues()).singleElement().satisfies(v -> {
            assertThat(v.layouts()).isEmpty();
            assertThat(v.facilities()).isEmpty();
        });
    }

    // ---- fixtures ---------------------------------------------------------

    private Event event(EventStatus status) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setOrganisation(ACME);
        event.setStatus(status);
        event.setCoordinatorId(COORDINATOR);
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T01:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T04:00:00Z"));
        event.setExpectedAttendance(150);
        event.setSafetyApprovedAt(OffsetDateTime.parse("2027-03-01T01:00:00Z"));
        if (status == EventStatus.confirmed) {
            event.setFirstConfirmedAt(OffsetDateTime.parse("2027-03-02T01:00:00Z"));
            event.setConfirmedAt(OffsetDateTime.parse("2027-03-02T01:00:00Z"));
        }
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private Venue venue(String name) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(name);
        venue.setVenueCapacity(200);
        venue.setSupportedLayouts(new ArrayList<>(List.of("theatre")));
        venue.setVenueAccessibilities(new ArrayList<>(List.of("step_free_access")));
        venue.setVenueFacilities(new ArrayList<>(List.of("projection")));
        venue.setOperatingInformation("Loading bay at the back.");
        when(venues.findById(venue.getVenueId())).thenReturn(Optional.of(venue));
        return venue;
    }

    private Venue approvedVenue(String name) {
        Venue venue = venue(name);
        booking(VenueBookingStatus.approved, venue);
        return venue;
    }

    private VenueBookingRecord approvedVenueBooking(String name) {
        return booking(VenueBookingStatus.approved, venue(name));
    }

    private VenueBookingRecord booking(VenueBookingStatus status, Venue venue) {
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setStatus(status);
        booking.setVenueId(venue == null ? UUID.randomUUID() : venue.getVenueId());
        eventBookings.add(booking);
        return booking;
    }

    private void approvedEquipment(String name, int quantity) {
        equipment(EquipmentRequestStatus.approved, name, quantity);
    }

    /** Equipment and its request lines have no public constructors, so these
     * are stubs: canned getters, nothing else. */
    private void equipment(EquipmentRequestStatus status, String name, int quantity) {
        UUID requestId = UUID.randomUUID();
        EquipmentRequest request = mock(EquipmentRequest.class);
        when(request.getId()).thenReturn(requestId);
        when(request.getStatus()).thenReturn(status);
        Equipment item = mock(Equipment.class);
        when(item.getName()).thenReturn(name);
        EquipmentRequestLine line = mock(EquipmentRequestLine.class);
        when(line.getEquipment()).thenReturn(item);
        when(line.getQuantity()).thenReturn(quantity);
        when(equipmentLines.findByIdRequestId(requestId)).thenReturn(List.of(line));
        eventEquipment.add(request);
    }
}
