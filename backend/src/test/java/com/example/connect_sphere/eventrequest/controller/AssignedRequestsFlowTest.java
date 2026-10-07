package com.example.connect_sphere.eventrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * ECL-C2 end to end through the real filter chain and a real PostgreSQL
 * database: what the Lead's assignments list returns as requests are
 * assigned and decided, and what the Lead finds on opening one of them.
 * Each test rolls back.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssignedRequestsFlowTest {

    private static final String ASSIGNED = "/api/event-requests/assigned";
    private static final String UNASSIGNED = "/api/event-requests/unassigned";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private UUID assignedTo(String coordinator, String organiser) throws Exception {
        return flow.submittedAndAssigned(organiser, coordinator, FlowSupport.requestBody("Town Hall", 150, "none"));
    }

    private void act(String username, String path, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/event-requests/" + path).with(flow.as(username))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    /** The Lead's list right now: one entry per coordinator. */
    private JsonNode assignments() throws Exception {
        String body = mvc.perform(get(ASSIGNED).with(flow.as("ecl1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body);
    }

    /** The requests listed under one coordinator, in the order returned. */
    private List<JsonNode> heldBy(String coordinator) throws Exception {
        JsonNode entry = assignments().valueStream()
                .filter(node -> node.get("coordinatorName").asString().equals(coordinator))
                .findFirst().orElseThrow();
        return entry.get("requests").valueStream().toList();
    }

    private List<String> idsHeldBy(String coordinator) throws Exception {
        return heldBy(coordinator).stream().map(node -> node.get("request").get("requestId").asString()).toList();
    }

    private static Instant instant(JsonNode node) {
        return OffsetDateTime.parse(node.asString()).toInstant();
    }

    private String statusOf(UUID id) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT status::text FROM event_requests WHERE request_id = ?", String.class, id);
    }

    private int timelineEntries(UUID id) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_request_activity WHERE request_id = ?", Integer.class, id);
    }

    @Test
    void theLeadSeesAnAssignedRequestsDetailsUnderItsCoordinator() throws Exception {
        UUID id = assignedTo("ec1", "eo3");

        JsonNode listed = heldBy("ec1").stream()
                .filter(node -> node.get("request").get("requestId").asString().equals(id.toString()))
                .findFirst().orElseThrow();
        JsonNode request = listed.get("request");

        assertThat(request.get("eventName").asString()).isEqualTo("Town Hall");
        assertThat(request.get("organisation").asString()).isEqualTo("Globex Holdings");
        assertThat(instant(request.get("startDatetime"))).isEqualTo(Instant.parse("2027-03-10T01:00:00Z"));
        assertThat(instant(request.get("endDatetime"))).isEqualTo(Instant.parse("2027-03-10T04:00:00Z"));
        assertThat(request.get("expectedAttendance").asInt()).isEqualTo(150);
        assertThat(request.get("status").asString()).isEqualTo("pending");
        assertThat(request.get("coordinatorId").asString()).isEqualTo(flow.idOf("ec1").toString());
        // Date submitted is the timeline's own record of the submission.
        String review = mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline[0].type").value("submitted"))
                .andReturn().getResponse().getContentAsString();
        assertThat(instant(listed.get("submittedAt")))
                .isEqualTo(instant(flow.read(review).get("timeline").get(0).get("occurredAt")));
        // Not under anyone else.
        assertThat(idsHeldBy("ec2")).doesNotContain(id.toString());
    }

    @Test
    void aNewlyAssignedRequestAppearsAndAnApprovedOrRejectedOneNoLongerDoes() throws Exception {
        int heldBefore = heldBy("ec1").size();

        UUID toApprove = assignedTo("ec1", "eo1");
        UUID toReject = assignedTo("ec1", "eo1");

        assertThat(idsHeldBy("ec1")).contains(toApprove.toString(), toReject.toString());
        assertThat(heldBy("ec1")).hasSize(heldBefore + 2);

        act("ec1", toApprove + "/approve", "{}", 200);

        assertThat(idsHeldBy("ec1")).contains(toReject.toString()).doesNotContain(toApprove.toString());
        assertThat(heldBy("ec1")).hasSize(heldBefore + 1);

        act("ec1", toReject + "/reject", "{\"reason\":\"No capacity\"}", 200);

        assertThat(idsHeldBy("ec1")).doesNotContain(toApprove.toString(), toReject.toString());
        assertThat(heldBy("ec1")).hasSize(heldBefore);
    }

    @Test
    void aRequestWaitingOnClarificationStaysListedAndTheLeadOpensItAsAssignedWithNoActions() throws Exception {
        UUID id = assignedTo("ec1", "eo1");
        act("ec1", id + "/clarifications", "{\"message\":\"Who is the event for?\"}", 200);
        int entriesBefore = timelineEntries(id);

        assertThat(heldBy("ec1"))
                .filteredOn(node -> node.get("request").get("requestId").asString().equals(id.toString()))
                .extracting(node -> node.get("request").get("status").asString())
                .containsExactly("clarification_required");
        // Opening it from the list: the review shows who has it.
        mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("clarification_required"))
                .andExpect(jsonPath("$.request.coordinatorId").value(flow.idOf("ec1").toString()));
        // And the Lead's own actions are refused, leaving it as it was.
        act("ecl1", "unassigned/" + id + "/reject", "{\"reason\":\"Not a corporate event\"}", 409);
        act("ecl1", "unassigned/" + id + "/clarifications", "{\"message\":\"And how many?\"}", 409);
        assertThat(statusOf(id)).isEqualTo("clarification_required");
        assertThat(timelineEntries(id)).isEqualTo(entriesBefore);
        assertThat(idsHeldBy("ec1")).contains(id.toString());
    }

    @Test
    void withNothingAssignedEveryCoordinatorIsStillListedHoldingNone() throws Exception {
        // Rolled back with the test: nothing under review has a coordinator.
        entityManager.flush();
        jdbc.update("UPDATE event_requests SET coordinator_id = NULL "
                + "WHERE status IN ('pending', 'clarification_required')");
        entityManager.clear();

        JsonNode assignments = assignments();

        assertThat(assignments.valueStream().map(node -> node.get("coordinatorName").asString()).toList())
                .contains("ec1", "ec2", "ec3", "ec4", "ec5");
        assertThat(assignments.valueStream().map(node -> node.get("requests").size()).toList())
                .containsOnly(0);
    }

    @Test
    void everyOtherSignedInRoleIsRefusedAndSeesNoRequestData() throws Exception {
        assignedTo("ec1", "eo1");

        for (String other : List.of("ec1", "eo1", "vs1", "ts1", "att1")) {
            mvc.perform(get(ASSIGNED).with(flow.as(other)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$[0]").doesNotExist());
        }
    }
}
