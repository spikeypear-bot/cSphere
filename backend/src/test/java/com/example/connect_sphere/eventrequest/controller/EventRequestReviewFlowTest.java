package com.example.connect_sphere.eventrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * EC01 / EC02 / EO26 end to end through the real filter chain and a real
 * PostgreSQL database: role gates, assignment checks, organisation scoping,
 * the timeline's audience filter and its append-only trigger. Each test rolls
 * back.
 *
 * <p>Notifications are sent after commit, and a rolled-back test never
 * commits, so they are covered by EventRequestClarificationServiceTest
 * instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventRequestReviewFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private UUID submittedRequest() throws Exception {
        return flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
    }

    private void clarify(UUID id, String coordinator, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/event-requests/" + id + "/clarifications").with(flow.as(coordinator))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    private List<String> timelineTypes(String url, String viewer) throws Exception {
        String body = mvc.perform(get(url).with(flow.as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body).valueStream().map(node -> node.get("type").asString()).toList();
    }

    /** Push pending JPA writes to the database (still inside the rolled-back
     * test transaction) so a direct SQL check sees them. */
    private void flush() {
        entityManager.flush();
    }

    private String statusOf(UUID id) {
        flush();
        return jdbc.queryForObject("SELECT status::text FROM event_requests WHERE request_id = ?", String.class, id);
    }

    @Test
    void aFullClarificationRoundTripIsRecordedInOrderFromSubmissionToApproval() throws Exception {
        UUID id = submittedRequest();

        clarify(id, "ec1",
                "{\"message\":\"Is 150 the final headcount?\",\"flaggedFields\":[\"expectedAttendance\"]}", 200);
        assertThat(statusOf(id)).isEqualTo("clarification_required");

        // The organiser sees the question and which field it is about.
        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].type").value("clarification_requested"))
                .andExpect(jsonPath("$[2].message").value("Is 150 the final headcount?"))
                .andExpect(jsonPath("$[2].flaggedFields[0]").value("expectedAttendance"))
                .andExpect(jsonPath("$[2].actorName").value("ec1"));

        // Editing while waiting keeps the status; resubmitting returns it.
        mvc.perform(put("/api/event-requests/" + id).with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FlowSupport.requestBody("Town Hall", 120, "none")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clarification_required"));
        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"120 confirmed.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.coordinatorId").value(flow.idOf("ec1").toString()));

        String approved = mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String eventId = flow.read(approved).get("eventId").asString();

        // The event page shows the whole journey, oldest first.
        assertThat(timelineTypes("/api/events/" + eventId + "/timeline", "ec1")).containsExactly(
                "submitted", "coordinator_assigned", "clarification_requested", "clarification_responded", "approved");
        assertThat(timelineTypes("/api/events/" + eventId + "/timeline", "eo1")).containsExactly(
                "submitted", "coordinator_assigned", "clarification_requested", "clarification_responded", "approved");
    }

    @Test
    void eachFlaggedFieldsQuestionAndTheValuesAtThatMomentReachTheOrganiser() throws Exception {
        UUID id = submittedRequest();

        clarify(id, "ec1", """
                {"message":"Expected attendance: Is 150 final?","flaggedFields":["expectedAttendance"],
                 "fieldQuestions":{"expectedAttendance":"Is 150 final?"}}""", 200);

        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[2].fieldQuestions.expectedAttendance").value("Is 150 final?"))
                .andExpect(jsonPath("$[2].fieldValues.expectedAttendance").value(150));
    }

    @Test
    void aQuestionAboutAnUnflaggedFieldOrABlankQuestionIsRefused() throws Exception {
        UUID id = submittedRequest();

        clarify(id, "ec1", """
                {"message":"x","flaggedFields":["purpose"],"fieldQuestions":{"eventName":"Why?"}}""", 422);
        clarify(id, "ec1", """
                {"message":"x","flaggedFields":["purpose","eventName"],"fieldQuestions":{"purpose":"Why?"}}""", 422);
        assertThat(statusOf(id)).isEqualTo("pending");
    }

    @Test
    void aRefusedResubmissionLeavesTheOrganisersEditsUnsaved() throws Exception {
        UUID id = submittedRequest();
        clarify(id, "ec1", "{\"message\":\"Please confirm.\"}", 200);

        String blankPurpose = FlowSupport.requestBody("Town Hall", 99, "none").replace("Quarterly update", "  ");
        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"Updated\",\"details\":" + blankPurpose + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("purpose"));

        flush();
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM event_requests WHERE request_id = ?",
                Integer.class, id)).isEqualTo(150);
        assertThat(statusOf(id)).isEqualTo("clarification_required");
    }

    @Test
    void aSuccessfulResubmissionSavesTheEditsAndRecordsTheNewValues() throws Exception {
        UUID id = submittedRequest();
        clarify(id, "ec1", "{\"message\":\"Is 150 final?\",\"flaggedFields\":[\"expectedAttendance\"]}", 200);

        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"120 confirmed\",\"details\":"
                                + FlowSupport.requestBody("Town Hall", 120, "none") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAttendance").value(120));

        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("ec1")))
                .andExpect(jsonPath("$.timeline[2].fieldValues.expectedAttendance").value(150))
                .andExpect(jsonPath("$.timeline[3].type").value("clarification_responded"))
                .andExpect(jsonPath("$.timeline[3].fieldValues.expectedAttendance").value(120));
    }

    @Test
    void decidingTwiceIsRefusedWithAPlainMessage() throws Exception {
        UUID id = submittedRequest();
        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1"))).andExpect(status().isOk());

        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This request has already been approved."));
    }

    @Test
    void theReviewScreenNamesTheOrganiserWhoCreatedTheRequest() throws Exception {
        UUID id = submittedRequest();

        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.createdByName").value("eo1"));
    }

    @Test
    void aRequestWaitingForClarificationCannotBeApprovedAndSaysWhy() throws Exception {
        UUID id = submittedRequest();
        clarify(id, "ec1", "{\"message\":\"Please confirm the date.\"}", 200);

        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This request is waiting for the organiser's clarification. It can be approved once they resubmit."));
        assertThat(statusOf(id)).isEqualTo("clarification_required");
    }

    @Test
    void aBlankClarificationIsRejectedAndLeavesNoTrace() throws Exception {
        UUID id = submittedRequest();

        clarify(id, "ec1", "{\"message\":\"   \"}", 422);

        assertThat(statusOf(id)).isEqualTo("pending");
        flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE request_id = ? "
                + "AND activity_type = 'clarification_requested'", Integer.class, id)).isZero();
    }

    @Test
    void aCoordinatorNotAssignedToTheRequestCannotOpenOrActOnIt() throws Exception {
        UUID id = submittedRequest();

        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        clarify(id, "ec2", "{\"message\":\"Not mine to ask\"}", 403);
        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec2")))
                .andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo("pending");
    }

    @Test
    void theReviewQueueShowsOnlyMyRequestsAndUnassignedOnes() throws Exception {
        UUID mine = submittedRequest();
        UUID theirs = flow.submittedAndAssigned("eo1", "ec2", FlowSupport.requestBody("Theirs", 50, "none"));

        String body = mvc.perform(get("/api/event-requests/queue").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains(mine.toString()).doesNotContain(theirs.toString());
    }

    @Test
    void anOrganiserCannotRequestClarificationOrOpenTheReviewScreen() throws Exception {
        UUID id = submittedRequest();

        clarify(id, "eo1", "{\"message\":\"Asking myself\"}", 403);
        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("eo1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOrganiserOfAnotherOrganisationCannotSeeTheTimelineOrResubmit() throws Exception {
        UUID id = submittedRequest();
        clarify(id, "ec1", "{\"message\":\"Please confirm.\"}", 200);

        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo3")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo3"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"Not mine\"}"))
                .andExpect(status().isNotFound());
        assertThat(statusOf(id)).isEqualTo("clarification_required");
    }

    @Test
    void timelineEntriesCannotBeEditedInTheDatabase() throws Exception {
        UUID id = submittedRequest();
        flush();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE event_request_activity SET message = 'rewritten' WHERE request_id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void timelineEntriesCannotBeDeletedInTheDatabase() throws Exception {
        UUID id = submittedRequest();
        flush();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM event_request_activity WHERE request_id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
