package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * EC03 end to end on real PostgreSQL through the real filter chain. The event
 * under test runs 2027-03-10 09:00-12:00 (+08:00), for 150 people, unless a
 * test says otherwise; other bookings are placed around that window to probe
 * the overlap boundaries. Every test rolls back.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VenueBookingRequestFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venueService;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private UUID event(int attendance, String accessibility) throws Exception {
        return flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", attendance, accessibility));
    }

    private UUID venue(int capacity, List<AccessibilityFeature> accessibility) {
        UUID id = venueService.createVenue(new CreateVenueDto("EC03 Hall " + UUID.randomUUID(), capacity,
                List.of(VenueLayout.theatre), "08:00-22:00 daily", null, accessibility, List.of())).venueId();
        entityManager.flush();
        return id;
    }

    private UUID searchableVenue(int capacity, List<Facility> facilities) {
        UUID id = venueService.createVenue(new CreateVenueDto("EC04 Hall " + UUID.randomUUID(), capacity,
                List.of(VenueLayout.theatre), "08:00-22:00 daily", null, List.of(), facilities)).venueId();
        entityManager.flush();
        return id;
    }

    /** Another event, already holding a booking of {@code venueId}. */
    private void otherBooking(UUID venueId, String start, String end, String status) {
        sync();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (event_id, event_name, purpose, start_datetime, end_datetime,
                    expected_attendance, venue_requirements, accessibility_needs, status)
                VALUES (?, 'Board Meeting', 'Other', ?::timestamptz, ?::timestamptz, 20, 'Boardroom',
                    '{}'::accessibilities[], 'confirmed')
                """, eventId, start, end);
        jdbc.update("INSERT INTO venue_bookings (booking_id, venue_id, event_id, status) "
                + "VALUES (?, ?, ?, cast(? as venue_booking_status))", UUID.randomUUID(), venueId, eventId, status);
    }

    private ResultActions submit(UUID eventId, String as, String body) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bookingFor(UUID venueId) {
        return "{\"venueId\":\"" + venueId + "\",\"bookingNotes\":\"Stage needed\"}";
    }

    /** Direct SQL and JPA share one rolled-back transaction: flush pending JPA
     * writes before SQL reads them, and clear the persistence context after
     * SQL changes rows JPA has cached. */
    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int bookingsFor(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    void attendanceEqualToCapacityCanBeBookedAndCreatesAPendingRequest() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(150, List.of());

        submit(eventId, "ec1", bookingFor(venueId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.bookingNotes").value("Stage needed"))
                .andExpect(jsonPath("$.submittedBy").value("ec1"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());
    }

    @Test
    void attendanceOneOverCapacityIsRefusedAndNothingIsCreated() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(149, List.of());

        submit(eventId, "ec1", bookingFor(venueId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Expected attendance (150) exceeds this venue's capacity (149)."));
        assertThat(bookingsFor(eventId)).isZero();
    }

    @Test
    void anApprovedBookingOverlappingTheEventBlocksTheVenue() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        otherBooking(venueId, "2027-03-10T11:59:00+08:00", "2027-03-10T14:00:00+08:00", "approved");

        submit(eventId, "ec1", bookingFor(venueId)).andExpect(status().isUnprocessableEntity());
        assertThat(bookingsFor(eventId)).isZero();
    }

    @Test
    void aBackToBackApprovedBookingIsNotAnOverlap() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        otherBooking(venueId, "2027-03-10T12:00:00+08:00", "2027-03-10T14:00:00+08:00", "approved");
        otherBooking(venueId, "2027-03-10T07:00:00+08:00", "2027-03-10T09:00:00+08:00", "approved");

        submit(eventId, "ec1", bookingFor(venueId)).andExpect(status().isCreated());
    }

    @Test
    void anotherEventsPendingRequestDoesNotBlockTheVenue() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        otherBooking(venueId, "2027-03-10T10:00:00+08:00", "2027-03-10T11:00:00+08:00", "pending");

        submit(eventId, "ec1", bookingFor(venueId)).andExpect(status().isCreated());
    }

    @Test
    void anEventCanHaveOnlyOneActiveRequestUntilThatOneIsRejected() throws Exception {
        UUID eventId = event(150, "none");
        UUID first = venue(200, List.of());
        UUID second = venue(300, List.of());
        submit(eventId, "ec1", bookingFor(first)).andExpect(status().isCreated());

        submit(eventId, "ec1", bookingFor(second)).andExpect(status().isConflict());
        assertThat(bookingsFor(eventId)).isEqualTo(1);

        sync();
        jdbc.update("UPDATE venue_bookings SET status = 'rejected' WHERE event_id = ?", eventId);
        sync();
        submit(eventId, "ec1", bookingFor(second)).andExpect(status().isCreated());
    }

    @Test
    void aVenueMissingARequestedAccessibilityFeatureNeedsAJustification() throws Exception {
        UUID eventId = event(150, "step_free_access");
        UUID venueId = venue(200, List.of(AccessibilityFeature.elevators));

        submit(eventId, "ec1", bookingFor(venueId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("step_free_access")));
        assertThat(bookingsFor(eventId)).isZero();

        submit(eventId, "ec1", "{\"venueId\":\"" + venueId + "\",\"suitabilityNote\":\"Ramp hire arranged\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suitabilityNote").value("Ramp hire arranged"));
    }

    @Test
    void submittingWithoutAVenueIsRefused() throws Exception {
        UUID eventId = event(150, "none");

        submit(eventId, "ec1", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Select a venue before submitting the booking request."));
    }

    @Test
    void onlyAnEventInPlanningCanTakeABookingRequest() throws Exception {
        UUID eventId = event(150, "none");
        sync();
        jdbc.update("UPDATE events SET status = 'confirmed' WHERE event_id = ?", eventId);
        sync();

        submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isConflict());
        assertThat(bookingsFor(eventId)).isZero();
    }

    @Test
    void onlyTheAssignedCoordinatorCanRequestOrSeeOptions() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());

        submit(eventId, "ec2", bookingFor(venueId)).andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/venue-options").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        assertThat(bookingsFor(eventId)).isZero();
    }

    @Test
    void organisersAndVenueStaffCannotUseTheCoordinatorEndpoints() throws Exception {
        UUID eventId = event(150, "none");

        mvc.perform(get("/api/events/" + eventId + "/venue-options").with(flow.as("eo1")))
                .andExpect(status().isForbidden());
        submit(eventId, "vs1", bookingFor(venue(200, List.of()))).andExpect(status().isForbidden());
    }

    @Test
    void venueOptionsListBookableVenuesFirstAndExplainWhyOthersAreBlocked() throws Exception {
        UUID eventId = event(150, "none");
        UUID tooSmall = venue(100, List.of());
        UUID tightFit = venue(160, List.of());
        UUID roomy = venue(900, List.of());

        String body = mvc.perform(get("/api/events/" + eventId + "/venue-options").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> order = flow.read(body).valueStream()
                .map(o -> o.get("venue").get("venueId").asString())
                .filter(id -> List.of(tooSmall.toString(), tightFit.toString(), roomy.toString()).contains(id))
                .toList();

        assertThat(order).containsExactly(tightFit.toString(), roomy.toString(), tooSmall.toString());
        String blocked = flow.read(body).valueStream()
                .filter(o -> o.get("venue").get("venueId").asString().equals(tooSmall.toString()))
                .findFirst().orElseThrow().get("verdict").asString();
        assertThat(blocked).isEqualTo("blocked");
    }

    @Test
    void venueSearchCombinesAvailabilityCapacityAndAllRequiredFacilities() throws Exception {
        UUID exactCapacity = searchableVenue(100, List.of(Facility.stage, Facility.projection));
        UUID insufficientCapacity = searchableVenue(99, List.of(Facility.stage, Facility.projection));
        UUID missingFacility = searchableVenue(150, List.of(Facility.stage));
        UUID overlappingBooking = searchableVenue(150, List.of(Facility.stage, Facility.projection));
        UUID adjacentBooking = searchableVenue(100, List.of(Facility.stage, Facility.projection));
        UUID pendingBooking = searchableVenue(100, List.of(Facility.stage, Facility.projection));
        otherBooking(overlappingBooking, "2027-03-10T11:59:00+08:00", "2027-03-10T14:00:00+08:00", "approved");
        otherBooking(adjacentBooking, "2027-03-10T06:00:00+08:00", "2027-03-10T09:00:00+08:00", "approved");
        otherBooking(pendingBooking, "2027-03-10T10:00:00+08:00", "2027-03-10T11:00:00+08:00", "pending");

        String body = mvc.perform(get("/api/venues/search").with(flow.as("ec1"))
                        .param("startDatetime", "2027-03-10T09:00:00+08:00")
                        .param("endDatetime", "2027-03-10T12:00:00+08:00")
                        .param("capacity", "100")
                        .param("facility", "stage", "projection"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> venueIds = flow.read(body).valueStream()
                .map(venue -> venue.get("venueId").asString())
                .toList();
        assertThat(venueIds).contains(
                exactCapacity.toString(), adjacentBooking.toString(), pendingBooking.toString());
        assertThat(venueIds).doesNotContain(
                insufficientCapacity.toString(), missingFacility.toString(), overlappingBooking.toString());
    }

    @Test
    void venueSearchRejectsInvalidDateTimeAndCapacityAndIsCoordinatorOnly() throws Exception {
        mvc.perform(get("/api/venues/search").with(flow.as("ec1"))
                        .param("startDatetime", "not-a-date")
                        .param("endDatetime", "2027-03-10T12:00:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("valid ISO-8601")));

        mvc.perform(get("/api/venues/search").with(flow.as("ec1"))
                        .param("startDatetime", "2027-03-10T09:00:00+08:00")
                        .param("endDatetime", "2027-03-10T12:00:00+08:00")
                        .param("capacity", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("greater than 0")));

        mvc.perform(get("/api/venues/search").with(flow.as("ec1"))
                        .param("startDatetime", "2027-03-10T12:00:00+08:00")
                        .param("endDatetime", "2027-03-10T09:00:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("after the start")));

        mvc.perform(get("/api/venues/search").with(flow.as("vs1"))
                        .param("startDatetime", "2027-03-10T09:00:00+08:00")
                        .param("endDatetime", "2027-03-10T12:00:00+08:00"))
                .andExpect(status().isForbidden());
    }

    private String bookingIdOf(ResultActions result) throws Exception {
        return flow.read(result.andReturn().getResponse().getContentAsString()).get("bookingId").asString();
    }

    private ResultActions cancel(UUID eventId, String bookingId, String as) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/venue-bookings/" + bookingId + "/cancel").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong venue\"}"));
    }

    @Test
    void theCoordinatorCanCancelAPendingRequestAndThenRequestAnotherVenue() throws Exception {
        UUID eventId = event(150, "none");
        String first = bookingIdOf(submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isCreated()));

        cancel(eventId, first, "ec1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));

        submit(eventId, "ec1", bookingFor(venue(300, List.of()))).andExpect(status().isCreated());
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[?(@.type == 'venue_booking_cancelled')].message")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Wrong venue"))));
    }

    @Test
    void anApprovedBookingCannotBeCancelledHere() throws Exception {
        UUID eventId = event(150, "none");
        String id = bookingIdOf(submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isCreated()));
        sync();
        jdbc.update("UPDATE venue_bookings SET status = 'approved' WHERE booking_id = ?::uuid", id);
        sync();

        cancel(eventId, id, "ec1").andExpect(status().isConflict());
    }

    @Test
    void onlyTheAssignedCoordinatorCanCancelAndOnlyForTheirOwnEvent() throws Exception {
        UUID eventId = event(150, "none");
        String id = bookingIdOf(submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isCreated()));
        UUID otherEvent = event(50, "none");

        cancel(eventId, id, "ec2").andExpect(status().isForbidden());
        cancel(otherEvent, id, "ec1").andExpect(status().isNotFound());
        cancel(eventId, id, "vs1").andExpect(status().isForbidden());
    }

    @Test
    void theRequestIsOnTheCoordinatorsTimelineButNotTheOrganisers() throws Exception {
        UUID eventId = event(150, "none");
        submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isCreated());

        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[-1].type").value("venue_booking_requested"));
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[?(@.type == 'venue_booking_requested')]").isEmpty());
    }
    @Test
    void staffRejectionIsVisibleToAssignedCoordinator() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        String created = submit(eventId, "ec1", bookingFor(venueId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = flow.read(created).get("bookingId").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/venue-bookings/" + id + "/reject")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("staff").roles("VS"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Maintenance\"}"))
                .andExpect(status().isOk());
        sync();
        mvc.perform(get("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("rejected"))
                .andExpect(jsonPath("$[0].rejectReason").value("Maintenance"));
    }
}
