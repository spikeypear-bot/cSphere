package com.example.connect_sphere.eventrequest.controller;

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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * ECL-C3 end to end through the real filter chain and a real PostgreSQL
 * database: the Lead's review, rejection and clarification of an unassigned
 * request, and what the organiser and the unassigned queue see afterwards.
 * Each test rolls back, so notifications (sent after commit) are covered by
 * EventRequestLeadReviewServiceTest instead.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeadRequestReviewFlowTest {

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

    private UUID unassignedRequest() throws Exception {
        return flow.submitted("eo1", FlowSupport.requestBody("Town Hall", 150, "none"));
    }

    private void leadPosts(UUID id, String action, String body, int expectedStatus) throws Exception {
        mvc.perform(post(UNASSIGNED + "/" + id + "/" + action).with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    /** Ids in the Lead's unassigned queue right now. */
    private List<String> unassignedQueue() throws Exception {
        String body = mvc.perform(get(UNASSIGNED).with(flow.as("ecl1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body).valueStream().map(node -> node.get("requestId").asString()).toList();
    }

    private String statusOf(UUID id) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT status::text FROM event_requests WHERE request_id = ?", String.class, id);
    }

    private String coordinatorOf(UUID id) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT coordinator_id::text FROM event_requests WHERE request_id = ?", String.class, id);
    }

    private int timelineEntries(UUID id) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_request_activity WHERE request_id = ?", Integer.class, id);
    }

    @Test
    void theLeadSeesAnUnassignedRequestAsTheOrganiserSubmittedIt() throws Exception {
        UUID id = unassignedRequest();

        mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.eventName").value("Town Hall"))
                .andExpect(jsonPath("$.request.organisation").value("Acme Pte Ltd"))
                .andExpect(jsonPath("$.request.createdByName").value("eo1"))
                .andExpect(jsonPath("$.request.purpose").value("Quarterly update"))
                .andExpect(jsonPath("$.request.description").value("All hands"))
                .andExpect(jsonPath("$.request.expectedAttendance").value(150))
                .andExpect(jsonPath("$.request.venueRequirements").value("Theatre seating"))
                .andExpect(jsonPath("$.request.accessibilityNeeds[0]").value("none"))
                .andExpect(jsonPath("$.request.registrationNeeds").value(false))
                .andExpect(jsonPath("$.request.status").value("pending"))
                .andExpect(jsonPath("$.timeline[0].type").value("submitted"));

        // Looking changes nothing.
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(coordinatorOf(id)).isNull();
        assertThat(timelineEntries(id)).isEqualTo(1);
    }

    @Test
    void aDraftOrUnknownRequestIsNotFoundForTheLead() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody("Town Hall", 150, "none"));

        mvc.perform(get(UNASSIGNED + "/" + draft).with(flow.as("ecl1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.request").doesNotExist());
        mvc.perform(get(UNASSIGNED + "/" + UUID.randomUUID()).with(flow.as("ecl1")))
                .andExpect(status().isNotFound());
        leadPosts(draft, "reject", "{\"reason\":\"Not a corporate event\"}", 404);
        assertThat(statusOf(draft)).isEqualTo("draft");
    }

    @Test
    void aRejectedRequestLeavesTheQueueAndTheOrganiserSeesTheReasonFromTheLead() throws Exception {
        UUID id = unassignedRequest();
        assertThat(unassignedQueue()).contains(id.toString());

        leadPosts(id, "reject", "{\"reason\":\"Not a corporate event\"}", 200);

        assertThat(statusOf(id)).isEqualTo("rejected");
        assertThat(coordinatorOf(id)).isNull();
        assertThat(unassignedQueue()).doesNotContain(id.toString());
        mvc.perform(get("/api/event-requests/" + id).with(flow.as("eo1")))
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("Not a corporate event"));
        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[1].type").value("rejected"))
                .andExpect(jsonPath("$[1].message").value("Not a corporate event"))
                .andExpect(jsonPath("$[1].actorName").value("ecl1"))
                .andExpect(jsonPath("$[1].actorRole").value("ecl"));
    }

    @Test
    void aRequestSentBackStaysInTheQueueMarkedClarificationRequiredUntilItIsResubmittedStillUnassigned()
            throws Exception {
        UUID id = unassignedRequest();

        leadPosts(id, "clarifications", "{\"message\":\"Who is the event for?\"}", 200);

        assertThat(statusOf(id)).isEqualTo("clarification_required");
        assertThat(coordinatorOf(id)).isNull();
        // ELC-C6: it stays listed while it waits, so the Lead can still assign it.
        mvc.perform(get(UNASSIGNED).with(flow.as("ecl1")))
                .andExpect(jsonPath("$[?(@.requestId == '" + id + "')].status").value("clarification_required"));
        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[1].type").value("clarification_requested"))
                .andExpect(jsonPath("$[1].message").value("Who is the event for?"))
                .andExpect(jsonPath("$[1].actorRole").value("ecl"));

        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"Our staff.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));

        assertThat(coordinatorOf(id)).isNull();
        assertThat(unassignedQueue()).contains(id.toString());
        // The Lead reads the organiser's answer on the review page.
        mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(jsonPath("$.timeline[2].type").value("clarification_responded"))
                .andExpect(jsonPath("$.timeline[2].message").value("Our staff."));
    }

    @Test
    void anEmptyReasonOrMessageIsRefusedAndNothingChanges() throws Exception {
        UUID id = unassignedRequest();

        leadPosts(id, "reject", "{\"reason\":\"   \"}", 422);
        leadPosts(id, "clarifications", "{\"message\":\"\"}", 422);

        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(timelineEntries(id)).isEqualTo(1);
        assertThat(unassignedQueue()).contains(id.toString());
    }

    @Test
    void onceARequestIsAssignedTheLeadsActionsAreRefusedAndNothingChanges() throws Exception {
        UUID id = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
        int entriesBefore = timelineEntries(id);

        leadPosts(id, "reject", "{\"reason\":\"Not a corporate event\"}", 409);
        leadPosts(id, "clarifications", "{\"message\":\"Who is the event for?\"}", 409);

        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(coordinatorOf(id)).isEqualTo(flow.idOf("ec1").toString());
        assertThat(timelineEntries(id)).isEqualTo(entriesBefore);
        // The Lead can still read it, and sees who has it.
        mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.coordinatorId").value(flow.idOf("ec1").toString()));
    }

    @Test
    void aRequestTheLeadAlreadyRejectedCannotBeRejectedOrSentBackAgain() throws Exception {
        UUID id = unassignedRequest();
        leadPosts(id, "reject", "{\"reason\":\"Not a corporate event\"}", 200);
        int entriesBefore = timelineEntries(id);

        leadPosts(id, "reject", "{\"reason\":\"Duplicate\"}", 409);
        leadPosts(id, "clarifications", "{\"message\":\"Who is the event for?\"}", 409);

        assertThat(statusOf(id)).isEqualTo("rejected");
        assertThat(timelineEntries(id)).isEqualTo(entriesBefore);
        mvc.perform(get("/api/event-requests/" + id).with(flow.as("eo1")))
                .andExpect(jsonPath("$.rejectionReason").value("Not a corporate event"));
    }
}
