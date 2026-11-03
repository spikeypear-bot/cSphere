package com.example.connect_sphere.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.notification.service.NotificationService;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

@Tag("integration")
@Tag("EC-C1")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventReassignmentFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoBean NotificationService notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    @Test
    @DisplayName("EC-C1 (happy): the coordinator asks to be reassigned, only the Lead sees the request, and approving moves the event and its original request to the new coordinator")
    void coordinatorRequestIsLeadOnlyAndApprovalMovesTheEventAndOriginatingRequest() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Reassignment Summit " + UUID.randomUUID(), 100, "none"));
        UUID requesterId = flow.idOf("ec1");
        UUID newCoordinatorId = flow.idOf("ec2");

        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I am no longer available.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.requesterUserId").value(requesterId.toString()));

        mvc.perform(get("/api/event-reassignment-requests").with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].reason")
                        .value(org.hamcrest.Matchers.hasItem("I am no longer available.")));
        mvc.perform(get("/api/event-reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not assigned.\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/event-reassignment-requests/"
                                + requestIdFor(eventId) + "/approve").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + newCoordinatorId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.coordinatorUserId").value(newCoordinatorId.toString()));

        mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("approved"));
        mvc.perform(get("/api/events/" + eventId).with(flow.as("ec2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinatorName").value("ec2"))
                .andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(get("/api/event-requests").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].coordinatorId")
                        .value(org.hamcrest.Matchers.hasItem(newCoordinatorId.toString())));
    }

    @Test
    @DisplayName("EC-C1 (conflict): a second reassignment request is refused while one is pending")
    void pendingRequestCannotBeSubmittedTwice() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Pending Reassignment " + UUID.randomUUID(), 100, "none"));
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Need a transfer.\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Second request.\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("EC-C1 (negative): a reason that is missing, blank or over 2000 characters is refused and no request is saved")
    void theReasonMustBeGivenAndAtMost2000Characters() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Reason Rules " + UUID.randomUUID(), 100, "none"));
        for (String body : new String[] {"{}", "{\"reason\":\"   \"}"}) {
            ask(eventId, body).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("Provide a reason for requesting reassignment."));
        }
        ask(eventId, "{\"reason\":\"" + "x".repeat(2001) + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This can be at most 2000 characters."));
        assertThat(requestRows(eventId)).isZero();

        ask(eventId, "{\"reason\":\"" + "x".repeat(2000) + "\"}").andExpect(status().isOk());
        assertThat(requestRows(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("EC-C1 (negative): a coordinator who neither holds the event nor asked for its transfer cannot read its reassignment requests")
    void aStrangerCannotReadTheEventsReassignmentRequests() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Stranger Read " + UUID.randomUUID(), 100, "none"));

        mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(NotAssignedCoordinatorException.MESSAGE));
        mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("EC-C1 (happy): the Lead picks the new coordinator from every Event Coordinator, in name order")
    void theLeadSeesEveryCoordinatorInNameOrder() throws Exception {
        List<String> expected = users.findByRole(UserRole.ec).stream().map(User::getUsername)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();

        String json = mvc.perform(get("/api/event-reassignment-requests/coordinators").with(flow.as("ecl1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> listed = new ArrayList<>();
        for (JsonNode coordinator : flow.read(json)) {
            listed.add(coordinator.get("username").asString());
        }
        assertThat(listed).isNotEmpty().containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("EC-C1 (negative): approval needs a different Event Coordinator; no one, the current one or a non-coordinator is refused and nothing changes")
    void approvalNeedsADifferentEventCoordinator() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Approval Choice " + UUID.randomUUID(), 100, "none"));
        ask(eventId, "{\"reason\":\"Need a transfer.\"}").andExpect(status().isOk());
        String requestId = requestIdFor(eventId);
        UUID organiser = flow.idOf("eo1");

        decide(requestId, "approve", "{}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("A coordinator must be specified"));
        decide(requestId, "approve", "{\"coordinatorUserId\":\"" + flow.idOf("ec1") + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Choose a different Event Coordinator."));
        decide(requestId, "approve", "{\"coordinatorUserId\":\"" + organiser + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("User " + organiser + " is not an Event Coordinator"));

        assertThat(requestStatus(requestId)).isEqualTo("pending");
        assertThat(eventCoordinator(eventId)).isEqualTo(flow.idOf("ec1").toString());
    }

    @Test
    @DisplayName("EC-C1 (conflict): once the Lead has moved the event to someone else, the earlier transfer request cannot be approved and stays pending")
    void aTransferRequestIsNotApprovedAfterTheLeadMovedTheEvent() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Moved Meanwhile " + UUID.randomUUID(), 100, "none"));
        ask(eventId, "{\"reason\":\"Need a transfer.\"}").andExpect(status().isOk());
        String requestId = requestIdFor(eventId);
        entityManager.flush();
        UUID originId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class,
                eventId);
        mvc.perform(post("/api/event-requests/assigned/" + originId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        decide(requestId, "approve", "{\"coordinatorUserId\":\"" + flow.idOf("ec1") + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "The event's assignment changed after this request was submitted; refresh the request."));

        assertThat(requestStatus(requestId)).isEqualTo("pending");
        assertThat(eventCoordinator(eventId)).isEqualTo(flow.idOf("ec2").toString());
    }

    @Test
    @DisplayName("EC-C1 (conflict): a request the Lead has already declined cannot be decided again and stays declined")
    void aDecidedRequestCannotBeDecidedAgain() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Decided Twice " + UUID.randomUUID(), 100, "none"));
        ask(eventId, "{\"reason\":\"Need a transfer.\"}").andExpect(status().isOk());
        String requestId = requestIdFor(eventId);
        decide(requestId, "decline", "{\"reason\":\"Please carry on.\"}").andExpect(status().isOk());

        decide(requestId, "approve", "{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This reassignment request has already been decided."));
        decide(requestId, "decline", "{\"reason\":\"Again.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This reassignment request has already been decided."));

        assertThat(requestStatus(requestId)).isEqualTo("declined");
        assertThat(eventCoordinator(eventId)).isEqualTo(flow.idOf("ec1").toString());
    }

    private ResultActions ask(UUID eventId, String body) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decide(String requestId, String decision, String body) throws Exception {
        return mvc.perform(post("/api/event-reassignment-requests/" + requestId + "/" + decision)
                .with(flow.as("ecl1")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int requestRows(UUID eventId) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM event_reassignment_requests WHERE event_id = ?",
                Integer.class, eventId);
    }

    private String requestStatus(String requestId) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT status FROM event_reassignment_requests WHERE reassignment_request_id = ?",
                String.class, UUID.fromString(requestId));
    }

    private String eventCoordinator(UUID eventId) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT coordinator_id::text FROM events WHERE event_id = ?", String.class,
                eventId);
    }

    private String requestIdFor(UUID eventId) throws Exception {
        String json = mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(json).get(0).get("reassignmentRequestId").asString();
    }
}
