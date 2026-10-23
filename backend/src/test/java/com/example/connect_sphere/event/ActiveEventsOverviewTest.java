package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * ECL-C4: the Event Coordinator Lead's view of every active event that has
 * been assigned, grouped by coordinator ({@code GET /api/events/by-coordinator}).
 * Real filter chain, real PostgreSQL, every test rolled back. Events are
 * created through the API (approval) and then moved to the status and dates
 * under test directly in the database, since the lifecycle steps in between
 * belong to other stories and are covered there.
 */
@Tag("integration")
@Tag("ECL-C4")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActiveEventsOverviewTest {

    private static final String OVERVIEW = "/api/events/by-coordinator";
    /** Fixed, far from today either way, so "date passed" never depends on when the test runs. */
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2020-01-15T09:00:00+08:00");
    private static final OffsetDateTime FAR_AHEAD = OffsetDateTime.parse("2035-06-01T09:00:00+08:00");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    /** An approved event (Planning) of {@code organiser}'s organisation, held by {@code coordinator}. */
    private UUID event(String organiser, String coordinator, String name) throws Exception {
        return flow.approvedEvent(organiser, coordinator, FlowSupport.requestBody(name, 120, "none"));
    }

    /** Moves an event to a status and start time (ending three hours later). */
    private void set(UUID eventId, String status, OffsetDateTime start) {
        entityManager.flush();
        jdbc.update("UPDATE events SET status = ?::event_status, start_datetime = ?, end_datetime = ? WHERE event_id = ?",
                status, start, start.plusHours(3), eventId);
        entityManager.clear();
    }

    private JsonNode overview() throws Exception {
        String body = mvc.perform(get(OVERVIEW).with(flow.as("ecl1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body);
    }

    private static JsonNode groupOf(JsonNode overview, String coordinator) {
        return overview.valueStream().filter(node -> node.get("coordinatorName").asString().equals(coordinator))
                .findFirst().orElseThrow(() -> new AssertionError(coordinator + " is not listed"));
    }

    /** The events listed under one coordinator, in the order returned. */
    private static List<JsonNode> eventsOf(JsonNode overview, String coordinator) {
        return groupOf(overview, coordinator).get("events").valueStream().map(held -> held.get("event")).toList();
    }

    private static List<String> idsOf(JsonNode overview, String coordinator) {
        return eventsOf(overview, coordinator).stream().map(event -> event.get("eventId").asString()).toList();
    }

    private static List<String> allIds(JsonNode overview) {
        return overview.valueStream().flatMap(group -> group.get("events").valueStream())
                .map(held -> held.get("event").get("eventId").asString()).toList();
    }

    private static JsonNode find(List<JsonNode> events, UUID eventId) {
        return events.stream().filter(event -> event.get("eventId").asString().equals(eventId.toString()))
                .findFirst().orElseThrow(() -> new AssertionError(eventId + " is not listed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "vs1", "ts1", "att1", "so1"})
    @DisplayName("ECL-C4 AC1 (negative): every other role is refused the active events overview, even calling the API directly")
    void everyOtherRoleIsRefused(String username) throws Exception {
        event("eo1", "ec1", "Private Gala");

        String body = mvc.perform(get(OVERVIEW).with(flow.as(username)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Private Gala");
    }

    @Test
    @DisplayName("ECL-C4 AC1 (happy): the signed-in Event Coordinator Lead can open the active events overview")
    void theLeadCanOpenIt() throws Exception {
        UUID id = event("eo1", "ec1", "Lead Visible Gala");

        assertThat(idsOf(overview(), "ec1")).contains(id.toString());
    }

    @Test
    @DisplayName("ECL-C4 AC2, AC4 (happy): events in all five active statuses from two organisations are listed under their coordinator with every field the Lead needs")
    void allFiveActiveStatusesAcrossOrganisations() throws Exception {
        Map<String, UUID> byStatus = new java.util.LinkedHashMap<>();
        byStatus.put("pending", event("eo1", "ec3", "Planning Fair"));
        byStatus.put("awaiting_safety_review", event("eo3", "ec3", "Review Fair"));
        byStatus.put("safety_changes_requested", event("eo1", "ec3", "Changes Fair"));
        byStatus.put("safety_approved", event("eo3", "ec3", "Approved Fair"));
        byStatus.put("confirmed", event("eo1", "ec3", "Confirmed Fair"));
        OffsetDateTime start = FAR_AHEAD;
        for (Map.Entry<String, UUID> entry : byStatus.entrySet()) {
            set(entry.getValue(), entry.getKey(), start);
            start = start.plusDays(1);
        }

        List<JsonNode> listed = eventsOf(overview(), "ec3");

        assertThat(find(listed, byStatus.get("pending")).get("status").asString()).isEqualTo("pending");
        assertThat(find(listed, byStatus.get("awaiting_safety_review")).get("status").asString())
                .isEqualTo("awaiting_safety_review");
        assertThat(find(listed, byStatus.get("safety_changes_requested")).get("status").asString())
                .isEqualTo("safety_changes_requested");
        assertThat(find(listed, byStatus.get("safety_approved")).get("status").asString()).isEqualTo("safety_approved");
        assertThat(find(listed, byStatus.get("confirmed")).get("status").asString()).isEqualTo("confirmed");
        assertThat(find(listed, byStatus.get("confirmed")).get("journeyStatus").get("code").asString())
                .isEqualTo("CONFIRMED");
        // Both organisations, across one coordinator.
        assertThat(find(listed, byStatus.get("pending")).get("organisation").asString()).isEqualTo("Acme Pte Ltd");
        assertThat(find(listed, byStatus.get("awaiting_safety_review")).get("organisation").asString())
                .isEqualTo("Globex Holdings");
        // AC4: name, organisation, start/end, attendance, status, coordinator.
        JsonNode review = find(listed, byStatus.get("awaiting_safety_review"));
        assertThat(review.get("eventName").asString()).isEqualTo("Review Fair");
        assertThat(OffsetDateTime.parse(review.get("startDatetime").asString()).toInstant())
                .isEqualTo(FAR_AHEAD.plusDays(1).toInstant());
        assertThat(OffsetDateTime.parse(review.get("endDatetime").asString()).toInstant())
                .isEqualTo(FAR_AHEAD.plusDays(1).plusHours(3).toInstant());
        assertThat(review.get("expectedAttendance").asInt()).isEqualTo(120);
        assertThat(review.get("coordinatorName").asString()).isEqualTo("ec3");
    }

    @Test
    @DisplayName("ECL-C4 AC3 (negative): cancelled, completed and finished confirmed events, and requests with no event yet, are not active events")
    void inactiveEventsAndRequestsAreAbsent() throws Exception {
        UUID cancelled = event("eo1", "ec3", "Cancelled Fair");
        set(cancelled, "cancelled", FAR_AHEAD);
        UUID completed = event("eo3", "ec3", "Completed Fair");
        set(completed, "completed", LONG_AGO);
        // A confirmed event whose end has passed has happened: it is Completed.
        UUID finished = event("eo1", "ec3", "Finished Fair");
        set(finished, "confirmed", LONG_AGO);
        UUID stillActive = event("eo3", "ec3", "Active Fair");
        set(stillActive, "pending", FAR_AHEAD);
        // Assigned but not yet approved, and a draft: handled in the request views.
        flow.submittedAndAssigned("eo1", "ec3", FlowSupport.requestBody("Unapproved Fair", 80, "none"));
        flow.draft("eo1", FlowSupport.requestBody("Draft Fair", 80, "none"));

        JsonNode overview = overview();

        assertThat(allIds(overview)).doesNotContain(cancelled.toString(), completed.toString(), finished.toString())
                .contains(stillActive.toString());
        assertThat(overview.toString()).doesNotContain("Unapproved Fair").doesNotContain("Draft Fair");
    }

    @Test
    @DisplayName("ECL-C4 AC5 (boundary): each coordinator's events are ordered by start date and time, soonest first, whatever order they were created in")
    void soonestFirst() throws Exception {
        UUID latest = event("eo1", "ec4", "Latest");
        set(latest, "pending", FAR_AHEAD.plusDays(30));
        UUID soonest = event("eo3", "ec4", "Soonest");
        set(soonest, "confirmed", FAR_AHEAD);
        // One minute after the soonest: the edge a date-only sort would get wrong.
        UUID middle = event("eo1", "ec4", "Middle");
        set(middle, "safety_approved", FAR_AHEAD.plusMinutes(1));

        List<String> mine = idsOf(overview(), "ec4").stream()
                .filter(id -> List.of(latest.toString(), soonest.toString(), middle.toString()).contains(id)).toList();

        assertThat(mine).containsExactly(soonest.toString(), middle.toString(), latest.toString());
    }

    @Test
    @DisplayName("ECL-C4 AC6 (boundary): every Event Coordinator is listed, including one holding no active events, so counts start at zero")
    void aCoordinatorWithNoActiveEventsIsStillListed() throws Exception {
        UUID ec5sOnly = event("eo1", "ec5", "Called Off");
        // Make ec5's holding exactly zero active events inside this (rolled back) test.
        entityManager.flush();
        jdbc.update("UPDATE events SET status = 'cancelled' WHERE coordinator_id = ?", flow.idOf("ec5"));
        entityManager.clear();
        UUID ec1s = event("eo3", "ec1", "Busy Fair");

        JsonNode overview = overview();

        assertThat(groupOf(overview, "ec5").get("events").size()).isZero();
        assertThat(allIds(overview)).doesNotContain(ec5sOnly.toString());
        assertThat(idsOf(overview, "ec1")).contains(ec1s.toString());
        List<String> coordinators = jdbc.queryForList(
                "SELECT username FROM users WHERE role = 'ec' ORDER BY username", String.class);
        assertThat(overview.valueStream().map(group -> group.get("coordinatorName").asString()).toList())
                .containsExactlyElementsOf(coordinators);
    }

    @Test
    @DisplayName("ECL-C4 AC8 (conflict): after the Lead reassigns an event, the next load lists it under the new coordinator only and both counts change by one")
    void aReassignedEventMovesToItsNewCoordinator() throws Exception {
        UUID eventId = event("eo1", "ec1", "Moving Fair");
        entityManager.flush();
        UUID requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class,
                eventId);
        JsonNode before = overview();
        int ec1Before = idsOf(before, "ec1").size();
        int ec2Before = idsOf(before, "ec2").size();

        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        JsonNode after = overview();
        assertThat(idsOf(after, "ec2")).contains(eventId.toString());
        assertThat(idsOf(after, "ec1")).doesNotContain(eventId.toString());
        assertThat(idsOf(after, "ec1")).hasSize(ec1Before - 1);
        assertThat(idsOf(after, "ec2")).hasSize(ec2Before + 1);
        assertThat(find(eventsOf(after, "ec2"), eventId).get("coordinatorName").asString()).isEqualTo("ec2");
    }

    @Test
    @DisplayName("ECL-C4 AC9 (boundary): an unconfirmed event whose date has passed stays listed and flagged; a future one is not flagged")
    void anUnconfirmedPastEventStaysFlagged() throws Exception {
        UUID overdue = event("eo1", "ec2", "Overdue Fair");
        set(overdue, "awaiting_safety_review", LONG_AGO);
        UUID upcoming = event("eo3", "ec2", "Upcoming Fair");
        set(upcoming, "awaiting_safety_review", FAR_AHEAD);

        List<JsonNode> listed = eventsOf(overview(), "ec2");

        assertThat(find(listed, overdue).get("journeyStatus").get("datePassed").asBoolean()).isTrue();
        assertThat(find(listed, overdue).get("journeyStatus").get("code").asString())
                .isEqualTo("AWAITING_SAFETY_REVIEW");
        assertThat(find(listed, upcoming).get("journeyStatus").get("datePassed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("ECL-C4 AC10 (cross-cutting): loading the overview changes no assignment, status, timeline entry or notification")
    void viewingChangesNothing() throws Exception {
        UUID overdue = event("eo1", "ec1", "Overdue Gala");
        set(overdue, "pending", LONG_AGO);
        event("eo3", "ec2", "Ordinary Gala");
        Map<String, Object> before = snapshot();

        overview();
        overview();

        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, Object> snapshot() {
        entityManager.flush();
        return Map.of(
                "events", jdbc.queryForList(
                        "SELECT event_id, status::text, coordinator_id FROM events ORDER BY event_id"),
                "requests", jdbc.queryForList(
                        "SELECT request_id, status::text, coordinator_id FROM event_requests ORDER BY request_id"),
                "timeline", jdbc.queryForObject("SELECT count(*) FROM event_request_activity", Long.class),
                "notifications", jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class));
    }
}
