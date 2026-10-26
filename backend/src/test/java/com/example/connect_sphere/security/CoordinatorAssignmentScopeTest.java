package com.example.connect_sphere.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * ECL-C7: an Event Coordinator may open, view or act on a request or event
 * only while the Lead has it assigned to them. This is the AC "an automated
 * test for every coordinator endpoint showing that a coordinator who is not
 * assigned is refused": each endpoint below is called by ec2 on ec1's
 * request or event and must answer 403 with the same plain message, show no
 * event data, and change nothing (statuses, timeline, notifications,
 * bookings, equipment requests). Real filter chain, real PostgreSQL; every
 * test rolls back.
 */
@Tag("integration") @Tag("VS17") @Tag("EC-C2")
@Tag("ECL-C7")
@Tag("ELC-C5")
@Tag("AU04")
@Tag("EO07")
@Tag("TS11")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CoordinatorAssignmentScopeTest {

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    /** ec1's event, with one venue booking request so cancel has something to refuse. */
    private record Owned(UUID requestId, UUID eventId, UUID bookingId) {
    }

    private Owned ec1sEvent() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Scope Gala", 120, "none"));
        entityManager.flush();
        UUID requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
        UUID venueId = venues.createVenue(new CreateVenueDto("Scope Hall " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Owned(requestId, eventId, UUID.fromString(flow.read(booking).get("bookingId").asString()));
    }

    private Map<String, Object> snapshot(Owned owned) {
        entityManager.flush();
        entityManager.clear();
        return jdbc.queryForMap("""
                SELECT (SELECT status::text FROM events WHERE event_id = ?) AS event_status,
                       (SELECT coordinator_id FROM events WHERE event_id = ?) AS event_coordinator,
                       (SELECT status::text FROM event_requests WHERE request_id = ?) AS request_status,
                       (SELECT count(*) FROM event_request_activity WHERE request_id = ?) AS timeline,
                       (SELECT count(*) FROM notifications) AS notifications,
                       (SELECT count(*) FROM venue_bookings WHERE event_id = ?) AS bookings,
                       (SELECT string_agg(status::text, ',') FROM venue_bookings WHERE event_id = ?) AS booking_statuses,
                       (SELECT count(*) FROM equipment_requests WHERE event_id = ?) AS equipment
                """, owned.eventId(), owned.eventId(), owned.requestId(), owned.requestId(), owned.eventId(),
                owned.eventId(), owned.eventId());
    }

    /** Every endpoint an Event Coordinator can call about one request or event. */
    private List<MockHttpServletRequestBuilder> coordinatorEndpoints(Owned o) {
        String event = "/api/events/" + o.eventId();
        String request = "/api/event-requests/" + o.requestId();
        return List.of(
                // Request review, clarification, approval, rejection (EC01/EC02)
                // (A request's history reaches the coordinator inside /review;
                // /timeline is the organiser's, closed to coordinators by role.)
                get(request + "/review"),
                post(request + "/clarifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Please confirm the date.\",\"flaggedFields\":[]}"),
                post(request + "/approve"),
                post(request + "/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"),
                // Event details and change log (EC09, EC-NEW5)
                get(event),
                get(event + "/timeline"),
                // Venue options, booking requests, cancellation, replacement (EC03/EC-C2/EC10)
                get(event + "/venue-options"),
                get(event + "/venue-bookings"),
                get(event + "/venue-bookings/" + o.bookingId() + "/history"),
                post(event + "/venue-bookings/" + o.bookingId() + "/withdraw-requirement").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not mine\"}"),
                post(event + "/venue-bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":\"" + UUID.randomUUID() + "\"}"),
                post(event + "/venue-bookings/batch").contentType(MediaType.APPLICATION_JSON).content("{\"requests\":[]}"),
                post(event + "/venue-bookings/" + o.bookingId() + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not mine\"}"),
                // Equipment requests (EC07)
                get(event + "/equipment-requests"),
                post(event + "/equipment-request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Mics\",\"items\":[]}"),
                // Safety review and confirmation (EC08), readiness (SO-C3 shared view)
                get(event + "/lifecycle"),
                get(event + "/readiness"),
                post(event + "/safety-review"),
                post(event + "/safety-review/withdraw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not mine\"}"),
                post(event + "/confirm"),
                // Technical Support's review outcomes for the event (TS11 AC12)
                get(event + "/equipment-change-reviews"),
                // Cancellation requests: read, approve, decline (EO07)
                get(event + "/cancellation-requests"),
                post(event + "/cancellation-requests/" + UUID.randomUUID() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                post(event + "/cancellation-requests/" + UUID.randomUUID() + "/decline")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not mine\"}"));
    }

    @Test
    @DisplayName("ECL-C7 AC1–AC7, AC22, AC23 (cross-cutting): every coordinator endpoint refuses a coordinator who is not assigned, shows no event data and changes nothing")
    void everyCoordinatorEndpointRefusesACoordinatorWhoIsNotAssigned() throws Exception {
        Owned owned = ec1sEvent();
        Map<String, Object> before = snapshot(owned);

        for (MockHttpServletRequestBuilder call : coordinatorEndpoints(owned)) {
            String body = mvc.perform(call.with(flow.as("ec2")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(NotAssignedCoordinatorException.MESSAGE))
                    .andReturn().getResponse().getContentAsString();
            // No event data, even indirectly: not the name, not the organiser.
            assertThat(body).doesNotContain("Scope Gala").doesNotContain("Acme");
        }

        assertThat(snapshot(owned)).isEqualTo(before);
    }

    @Test
    @DisplayName("ECL-C7 AC1 (happy): the assigned coordinator reaches the same endpoints")
    void theAssignedCoordinatorIsLetIn() throws Exception {
        Owned owned = ec1sEvent();

        for (String path : List.of("/api/events/" + owned.eventId(), "/api/events/" + owned.eventId() + "/timeline",
                "/api/events/" + owned.eventId() + "/lifecycle", "/api/events/" + owned.eventId() + "/venue-bookings",
                "/api/events/" + owned.eventId() + "/equipment-requests")) {
            mvc.perform(get(path).with(flow.as("ec1"))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("ECL-C7 AC12, AC13 (negative): no coordinator can open or act on a request nobody is assigned to; only the Lead sees it")
    void anUnassignedRequestIsClosedToEveryCoordinator() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Nobody's Fair", 80, "none"));

        for (String coordinator : List.of("ec1", "ec2")) {
            mvc.perform(get("/api/event-requests/" + requestId + "/review").with(flow.as(coordinator)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as(coordinator)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/event-requests/unassigned").with(flow.as(coordinator)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/event-requests/unassigned/" + requestId).with(flow.as("ecl1"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ECL-C7 AC20, AC21 (cross-cutting): the Lead can view any event but cannot perform coordinator actions on it")
    void theLeadCanViewButNotAct() throws Exception {
        Owned owned = ec1sEvent();
        String event = "/api/events/" + owned.eventId();
        Map<String, Object> before = snapshot(owned);

        for (String path : List.of(event, event + "/timeline", event + "/venue-bookings", event + "/equipment-requests")) {
            mvc.perform(get(path).with(flow.as("ecl1"))).andExpect(status().isOk());
        }
        for (MockHttpServletRequestBuilder action : List.of(
                post("/api/event-requests/" + owned.requestId() + "/approve"),
                post(event + "/venue-bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":\"" + UUID.randomUUID() + "\"}"),
                post(event + "/safety-review"),
                post(event + "/confirm"))) {
            mvc.perform(action.with(flow.as("ecl1"))).andExpect(status().isForbidden());
        }
        assertThat(snapshot(owned)).isEqualTo(before);
    }

    @Test
    @DisplayName("ECL-C7 AC14, AC15, AC16, AC8, AC9 (conflict): reassigning an event moves access at once; the previous coordinator is refused and each list and total follows")
    void reassigningAnEventMovesAccessAtOnce() throws Exception {
        Owned owned = ec1sEvent();
        int ec1Before = myEventsCount("ec1");
        int ec2Before = myEventsCount("ec2");

        mvc.perform(post("/api/event-requests/assigned/" + owned.requestId() + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());

        // The event itself now belongs to ec2 (the source of truth once it exists).
        assertThat(snapshot(owned).get("event_coordinator")).isEqualTo(flow.idOf("ec2"));
        // ec1 is refused everywhere, including an action they had open before.
        Map<String, Object> before = snapshot(owned);
        mvc.perform(get("/api/events/" + owned.eventId()).with(flow.as("ec1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(NotAssignedCoordinatorException.MESSAGE));
        mvc.perform(post("/api/events/" + owned.eventId() + "/venue-bookings/" + owned.bookingId() + "/cancel")
                        .with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"late\"}"))
                .andExpect(status().isForbidden());
        assertThat(snapshot(owned)).isEqualTo(before);
        // ec2 is let in at the same moment.
        mvc.perform(get("/api/events/" + owned.eventId()).with(flow.as("ec2"))).andExpect(status().isOk());
        // My events (EC09) follow the assignment.
        mvc.perform(get("/api/events/assigned").with(flow.as("ec1")))
                .andExpect(jsonPath("$[?(@.eventId == '" + owned.eventId() + "')]").isEmpty());
        mvc.perform(get("/api/events/assigned").with(flow.as("ec2")))
                .andExpect(jsonPath("$[?(@.eventId == '" + owned.eventId() + "')]").isNotEmpty());
        // Totals count only each coordinator's own events (AC9).
        assertThat(myEventsCount("ec1")).isEqualTo(ec1Before - 1);
        assertThat(myEventsCount("ec2")).isEqualTo(ec2Before + 1);
        // The Lead's overview agrees.
        mvc.perform(get("/api/events/by-coordinator").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[?(@.coordinatorName == 'ec2')].events[*].event.eventId",
                        org.hamcrest.Matchers.hasItem(owned.eventId().toString())))
                .andExpect(jsonPath("$[?(@.coordinatorName == 'ec1')].events[*].event.eventId",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(owned.eventId().toString()))));
    }

    private int myEventsCount(String coordinator) throws Exception {
        String body = mvc.perform(get("/api/events/assigned").with(flow.as(coordinator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body).size();
    }

    @Test
    @DisplayName("ECL-C7 AC14 (boundary): a cancelled event can no longer be reassigned")
    void aCancelledEventCannotBeReassigned() throws Exception {
        Owned owned = ec1sEvent();
        entityManager.flush();
        jdbc.update("UPDATE events SET status = 'cancelled' WHERE event_id = ?", owned.eventId());
        entityManager.clear();

        mvc.perform(post("/api/event-requests/assigned/" + owned.requestId() + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isConflict());
        assertThat(snapshot(owned).get("event_coordinator")).isEqualTo(flow.idOf("ec1"));
    }

    @Test
    @DisplayName("ECL-C7 AC10, AC11 (negative): another event's booking shows only as \"Booked by another event\" with its time, never its name")
    void anotherEventsBookingShowsWithoutItsName() throws Exception {
        Owned owned = ec1sEvent();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/venue-bookings/" + owned.bookingId() + "/approve").with(flow.as("vs1")))
                .andExpect(status().isOk());
        UUID venueId = jdbc.queryForObject("SELECT venue_id FROM venue_bookings WHERE booking_id = ?", UUID.class,
                owned.bookingId());
        // ec2's own event at the same time.
        UUID ec2Event = flow.approvedEvent("eo3", "ec2", FlowSupport.requestBody("Other Fair", 50, "none"));
        entityManager.flush();

        String body = mvc.perform(get("/api/events/" + ec2Event + "/venue-options").with(flow.as("ec2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venue.venueId == '" + venueId + "')].conflicts[0].description")
                        .value(org.hamcrest.Matchers.hasItem("Booked by another event")))
                .andExpect(jsonPath("$[?(@.venue.venueId == '" + venueId + "')].reasons[*]",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Booked by another event, 10 Mar 2027"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Scope Gala").doesNotContain("Acme");
    }

    @Test
    @DisplayName("ECL-C7 AC10, AC13 (negative): the Lead's overview of every coordinator's events is for the Lead only")
    void theEventsOverviewIsForTheLeadOnly() throws Exception {
        for (String other : List.of("ec1", "eo1", "vs1", "so1")) {
            mvc.perform(get("/api/events/by-coordinator").with(flow.as(other))).andExpect(status().isForbidden());
        }
    }
}
