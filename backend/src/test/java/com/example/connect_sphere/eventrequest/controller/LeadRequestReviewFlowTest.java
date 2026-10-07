package com.example.connect_sphere.eventrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * ECL-C3 and ELC-C6 end to end through the real filter chain and a real
 * PostgreSQL database: the Lead's review, rejection, clarification and
 * assignment of an unassigned request, and what the organiser, the
 * coordinator and the unassigned queue see afterwards. Each test rolls back,
 * so notifications (sent after commit) are covered by
 * EventRequestLeadReviewServiceTest and NotificationFlowTest instead.
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

    private void leadAssigns(UUID id, String coordinatorUserId, int expectedStatus) throws Exception {
        leadPosts(id, "assign", "{\"coordinatorUserId\":" + coordinatorUserId + "}", expectedStatus);
    }

    /** A seeded account's id as a JSON string. */
    private String quoted(String username) {
        return "\"" + flow.idOf(username) + "\"";
    }

    /** Ids in one list of a coordinator's own review queue right now. */
    private List<String> reviewQueue(String coordinator, String list) throws Exception {
        String body = mvc.perform(get("/api/event-requests/queue").with(flow.as(coordinator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body).get(list).valueStream().map(node -> node.get("requestId").asString()).toList();
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

    // ---- ELC-C6: assigning --------------------------------------------

    @Test
    void theLeadPicksFromEveryEventCoordinatorAndNobodyElse() throws Exception {
        List<String> everyCoordinator = jdbc.queryForList(
                "SELECT username FROM users WHERE role = 'ec' ORDER BY username", String.class);

        String body = mvc.perform(get(UNASSIGNED + "/coordinators").with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'ec1')].userId").value(flow.idOf("ec1").toString()))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].hashedPassword").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        List<String> offered = flow.read(body).valueStream().map(node -> node.get("username").asString()).toList();
        assertThat(offered).containsExactlyInAnyOrderElementsOf(everyCoordinator).isSorted();
        assertThat(offered).contains("ec1", "ec2", "ec3", "ec4", "ec5").doesNotContain("ecl1", "eo1", "vs1");
    }

    @Test
    void anAssignedRequestKeepsItsStatusLeavesTheUnassignedListAndJoinsItsCoordinatorsQueue() throws Exception {
        UUID id = unassignedRequest();

        mvc.perform(post(UNASSIGNED + "/" + id + "/assign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":" + quoted("ec1") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.coordinatorId").value(flow.idOf("ec1").toString()));

        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(coordinatorOf(id)).isEqualTo(flow.idOf("ec1").toString());
        assertThat(unassignedQueue()).doesNotContain(id.toString());
        assertThat(reviewQueue("ec1", "needsReview")).contains(id.toString());
        assertThat(reviewQueue("ec2", "needsReview")).doesNotContain(id.toString());
        // The Lead still sees who has it.
        mvc.perform(get(UNASSIGNED + "/" + id).with(flow.as("ecl1")))
                .andExpect(jsonPath("$.request.coordinatorId").value(flow.idOf("ec1").toString()));
    }

    @Test
    void theAssignedCoordinatorReviewsAndApprovesTheRequestAsBefore() throws Exception {
        UUID id = unassignedRequest();
        leadAssigns(id, quoted("ec1"), 200);

        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.eventName").value("Town Hall"));
        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    void assigningIsOnTheTimelineWithTheLeadAsItsActorAndTheCoordinatorsName() throws Exception {
        UUID id = unassignedRequest();

        leadAssigns(id, quoted("ec2"), 200);

        assertThat(timelineEntries(id)).isEqualTo(2);
        mvc.perform(get("/api/event-requests/" + id + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[1].type").value("coordinator_assigned"))
                .andExpect(jsonPath("$[1].actorName").value("ecl1"))
                .andExpect(jsonPath("$[1].actorRole").value("ecl"))
                .andExpect(jsonPath("$[1].message").value(containsString("ec2")));
    }

    @Test
    void assigningChangesNoDetailOfTheRequestAndCreatesNoEvent() throws Exception {
        UUID id = unassignedRequest();
        int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM events", Integer.class);

        leadAssigns(id, quoted("ec1"), 200);

        mvc.perform(get("/api/event-requests/" + id).with(flow.as("eo1")))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.eventId").value(nullValue()))
                .andExpect(jsonPath("$.eventName").value("Town Hall"))
                .andExpect(jsonPath("$.purpose").value("Quarterly update"))
                .andExpect(jsonPath("$.description").value("All hands"))
                .andExpect(jsonPath("$.expectedAttendance").value(150))
                .andExpect(jsonPath("$.venueRequirements").value("Theatre seating"))
                .andExpect(jsonPath("$.accessibilityNeeds[0]").value("none"))
                .andExpect(jsonPath("$.registrationNeeds").value(false))
                .andExpect(jsonPath("$.rejectionReason").value(nullValue()));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isEqualTo(eventsBefore);
    }

    @Test
    void aRequestAssignedWhileItWaitsForClarificationGoesToItsCoordinatorWhenResubmitted() throws Exception {
        UUID id = unassignedRequest();
        leadPosts(id, "clarifications", "{\"message\":\"Who is the event for?\"}", 200);
        assertThat(unassignedQueue()).contains(id.toString());

        leadAssigns(id, quoted("ec1"), 200);

        assertThat(statusOf(id)).isEqualTo("clarification_required");
        assertThat(coordinatorOf(id)).isEqualTo(flow.idOf("ec1").toString());
        assertThat(unassignedQueue()).doesNotContain(id.toString());
        assertThat(reviewQueue("ec1", "awaitingOrganiser")).contains(id.toString());

        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"Our staff.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));

        assertThat(coordinatorOf(id)).isEqualTo(flow.idOf("ec1").toString());
        assertThat(unassignedQueue()).doesNotContain(id.toString());
        assertThat(reviewQueue("ec1", "needsReview")).contains(id.toString());
    }

    @Test
    void assigningWithoutACoordinatorIsRefusedWithAMessageAndNothingChanges() throws Exception {
        UUID id = unassignedRequest();

        for (String body : List.of("{}", "{\"coordinatorUserId\":null}")) {
            mvc.perform(post(UNASSIGNED + "/" + id + "/assign").with(flow.as("ecl1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("A coordinator must be specified"));
        }

        assertUnassignedAndUntouched(id);
    }

    @Test
    void assigningSomeoneWhoIsNotAnEventCoordinatorIsRefusedWithAMessageAndNothingChanges() throws Exception {
        UUID id = unassignedRequest();

        for (String notACoordinator : List.of(quoted("ecl1"), quoted("eo1"), quoted("vs1"),
                "\"" + UUID.randomUUID() + "\"")) {
            mvc.perform(post(UNASSIGNED + "/" + id + "/assign").with(flow.as("ecl1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"coordinatorUserId\":" + notACoordinator + "}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(containsString("is not an Event Coordinator")));
        }

        assertUnassignedAndUntouched(id);
    }

    @Test
    void aRequestThatAlreadyHasACoordinatorCannotBeAssignedAgain() throws Exception {
        UUID id = unassignedRequest();
        leadAssigns(id, quoted("ec1"), 200);
        int entriesBefore = timelineEntries(id);

        leadAssigns(id, quoted("ec2"), 409);
        leadAssigns(id, quoted("ec1"), 409);

        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(coordinatorOf(id)).isEqualTo(flow.idOf("ec1").toString());
        assertThat(timelineEntries(id)).isEqualTo(entriesBefore);
        assertThat(reviewQueue("ec2", "needsReview")).doesNotContain(id.toString());
    }

    @Test
    void aRequestTheLeadAlreadyRejectedCannotBeAssigned() throws Exception {
        UUID id = unassignedRequest();
        leadPosts(id, "reject", "{\"reason\":\"Not a corporate event\"}", 200);
        int entriesBefore = timelineEntries(id);

        leadAssigns(id, quoted("ec1"), 409);

        assertThat(statusOf(id)).isEqualTo("rejected");
        assertThat(coordinatorOf(id)).isNull();
        assertThat(timelineEntries(id)).isEqualTo(entriesBefore);
        assertThat(reviewQueue("ec1", "needsReview")).doesNotContain(id.toString());
    }

    @Test
    void aDraftOrUnknownRequestCannotBeAssigned() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody("Town Hall", 150, "none"));

        leadAssigns(draft, quoted("ec1"), 404);
        leadAssigns(UUID.randomUUID(), quoted("ec1"), 404);

        assertThat(statusOf(draft)).isEqualTo("draft");
        assertThat(coordinatorOf(draft)).isNull();
        assertThat(timelineEntries(draft)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ec1", "eo1", "vs1", "ts1", "att1"})
    void nobodyButTheLeadCanAssignEvenByCallingTheApiDirectly(String username) throws Exception {
        UUID id = unassignedRequest();

        mvc.perform(post(UNASSIGNED + "/" + id + "/assign").with(flow.as(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":" + quoted("ec1") + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertUnassignedAndUntouched(id);
    }

    /** A refused assignment: still submitted, nobody assigned, only the
     * submission on the timeline, and still in the unassigned list. */
    private void assertUnassignedAndUntouched(UUID id) throws Exception {
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(coordinatorOf(id)).isNull();
        assertThat(timelineEntries(id)).isEqualTo(1);
        assertThat(unassignedQueue()).contains(id.toString());
    }
}
