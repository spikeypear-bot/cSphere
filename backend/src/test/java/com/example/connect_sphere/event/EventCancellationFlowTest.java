package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * EO07 on real PostgreSQL through the real filter chain, set up through the
 * API (FlowSupport) so the steps before cancellation are proved to work with
 * it. Every test rolls back, so notifications (sent after commit) are not
 * written here; EventCancellationServiceTest checks those.
 */
@Tag("integration")
@Tag("EO07")
@Tag("ECL-C1")
@Tag("EC02")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventCancellationFlowTest {

    private static final String REASON_BODY = "{\"reason\":\"The client has called the event off.\"}";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private ResultActions cancelRequest(String as, UUID requestId, String body) throws Exception {
        return mvc.perform(post("/api/event-requests/" + requestId + "/cancel").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> requestState(UUID requestId) {
        sync();
        return jdbc.queryForMap("""
                SELECT status::text AS status, coordinator_id, updated_at,
                       (SELECT count(*) FROM event_request_activity WHERE request_id = r.request_id) AS timeline
                FROM event_requests r WHERE request_id = ?
                """, requestId);
    }

    // ---- Before approval (AC1, AC2, AC4, AC5) ---------------------------------

    @Test
    @DisplayName("EO07 AC2, AC11 (happy): an organiser cancels a request under review; it shows Cancelled with the reason on its timeline as a Major change")
    void anOrganiserCancelsARequestUnderReview() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Spring Gala", 80, "none"));

        cancelRequest("eo1", requestId, REASON_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.journeyStatus.label").value("Cancelled"));

        assertThat(requestState(requestId).get("status")).isEqualTo("cancelled");
        mvc.perform(get("/api/event-requests/" + requestId + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[-1].type").value("request_cancelled"))
                .andExpect(jsonPath("$[-1].message").value("The client has called the event off."))
                .andExpect(jsonPath("$[-1].actorName").value("eo1"))
                .andExpect(jsonPath("$[-1].significance").value("major"));
    }

    @Test
    @DisplayName("EO07 AC2 (happy): a cancelled request leaves the Lead's unassigned queue and can no longer be assigned")
    void aCancelledRequestLeavesTheLeadsQueueAndCannotBeAssigned() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Unassigned Fair", 80, "none"));
        mvc.perform(get("/api/event-requests/unassigned").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].requestId", hasItem(requestId.toString())));

        cancelRequest("eo1", requestId, REASON_BODY).andExpect(status().isOk());

        mvc.perform(get("/api/event-requests/unassigned").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].requestId", not(hasItem(requestId.toString()))));
        Map<String, Object> before = requestState(requestId);
        mvc.perform(post("/api/event-requests/unassigned/" + requestId + "/assign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec1") + "\"}"))
                .andExpect(status().isConflict());
        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC2, AC12 (happy): a cancelled request leaves the coordinator's review queue and can no longer be edited, approved, rejected or reassigned")
    void aCancelledRequestLeavesTheReviewQueueAndIsFinal() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Review Fair", 80, "none"));

        cancelRequest("eo1", requestId, REASON_BODY).andExpect(status().isOk());

        mvc.perform(get("/api/event-requests/queue").with(flow.as("ec1")))
                .andExpect(jsonPath("$.needsReview[*].requestId", not(hasItem(requestId.toString()))))
                .andExpect(jsonPath("$.awaitingOrganiser[*].requestId", not(hasItem(requestId.toString()))));
        Map<String, Object> before = requestState(requestId);
        mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/event-requests/" + requestId + "/reject").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/event-requests/" + requestId).with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FlowSupport.requestBody("Edited Fair", 90, "none")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isConflict());
        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (negative): an organiser of another organisation cannot cancel the request, and nothing changes")
    void anotherOrganisationsOrganiserCannotCancelARequest() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Acme Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        cancelRequest("eo3", requestId, REASON_BODY).andExpect(status().isNotFound());

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (cross-cutting): no role but the Event Organiser can cancel a request, and nothing changes")
    void otherRolesCannotCancelARequest() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Role Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        for (String other : List.of("ec1", "ecl1", "vs1", "ts1", "att1", "so1")) {
            cancelRequest(other, requestId, REASON_BODY).andExpect(status().isForbidden());
        }

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC4 (negative): a blank reason is refused with a clear message and the request is unchanged")
    void aBlankReasonIsRefused() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Blank Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        cancelRequest("eo1", requestId, "{\"reason\":\"   \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Please give a reason for cancelling this request."));

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC5 (negative): a Draft or an already Cancelled request cannot be cancelled, and nothing changes")
    void aDraftOrCancelledRequestCannotBeCancelled() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody("Draft Fair", 80, "none"));
        Map<String, Object> draftBefore = requestState(draft);
        cancelRequest("eo1", draft, REASON_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("this one is Draft")));
        assertThat(requestState(draft)).isEqualTo(draftBefore);

        UUID cancelled = flow.submitted("eo1", FlowSupport.requestBody("Twice Fair", 80, "none"));
        cancelRequest("eo1", cancelled, REASON_BODY).andExpect(status().isOk());
        Map<String, Object> cancelledBefore = requestState(cancelled);
        cancelRequest("eo1", cancelled, REASON_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("this one is Cancelled")));
        assertThat(requestState(cancelled)).isEqualTo(cancelledBefore);
    }
}
