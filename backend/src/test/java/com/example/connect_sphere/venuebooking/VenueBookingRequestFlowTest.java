package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
    void aConfirmedBookingOverlappingTheEventBlocksTheVenue() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        otherBooking(venueId, "2027-03-10T11:59:00+08:00", "2027-03-10T14:00:00+08:00", "confirmed");

        submit(eventId, "ec1", bookingFor(venueId)).andExpect(status().isUnprocessableEntity());
        assertThat(bookingsFor(eventId)).isZero();
    }

    @Test
    void aBackToBackConfirmedBookingIsNotAnOverlap() throws Exception {
        UUID eventId = event(150, "none");
        UUID venueId = venue(200, List.of());
        otherBooking(venueId, "2027-03-10T12:00:00+08:00", "2027-03-10T14:00:00+08:00", "confirmed");
        otherBooking(venueId, "2027-03-10T07:00:00+08:00", "2027-03-10T09:00:00+08:00", "confirmed");

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
    void theRequestIsOnTheCoordinatorsTimelineButNotTheOrganisers() throws Exception {
        UUID eventId = event(150, "none");
        submit(eventId, "ec1", bookingFor(venue(200, List.of()))).andExpect(status().isCreated());

        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[-1].type").value("venue_booking_requested"));
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[?(@.type == 'venue_booking_requested')]").isEmpty());
    }
}
