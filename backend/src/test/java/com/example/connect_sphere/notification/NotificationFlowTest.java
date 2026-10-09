package com.example.connect_sphere.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("integration")
@Tag("EO09")
@Tag("EO19")
@SpringBootTest
@AutoConfigureMockMvc
class NotificationFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private FlowSupport flow;
    private String eventName;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        eventName = "EO09 Notice " + UUID.randomUUID();
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE event_request_activity DISABLE TRIGGER trg_activity_append_only");
            jdbc.update("DELETE FROM notifications WHERE event_name = ?", eventName);
            jdbc.update("""
                    DELETE FROM event_request_activity
                    WHERE request_id IN (SELECT request_id FROM event_requests WHERE event_name = ?)
                    """, eventName);
            jdbc.update("DELETE FROM event_requests WHERE event_name = ?", eventName);
            jdbc.update("DELETE FROM events WHERE event_name = ?", eventName);
            jdbc.execute("ALTER TABLE event_request_activity ENABLE TRIGGER trg_activity_append_only");
        });
    }

    private UUID assignedRequest() throws Exception {
        return flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody(eventName, 150, "none"));
    }

    private UUID approve(UUID requestId) throws Exception {
        String response = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(response).get("eventId").asString());
    }

    private ResultActions assign(UUID requestId, String coordinator) throws Exception {
        return mvc.perform(post("/api/event-requests/unassigned/" + requestId + "/assign").with(flow.as("ecl1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"coordinatorUserId\":\"" + flow.idOf(coordinator) + "\"}"));
    }

    private ResultActions reject(UUID requestId, String reason) throws Exception {
        return mvc.perform(post("/api/event-requests/" + requestId + "/reject").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions confirm(UUID eventId, String as) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/confirm").with(flow.as(as)));
    }

    private ResultActions notificationsOf(String username) throws Exception {
        return mvc.perform(get("/api/notifications").with(flow.as(username))).andExpect(status().isOk());
    }

    private int unreadCountOf(String username) throws Exception {
        String response = mvc.perform(get("/api/notifications/unread-count").with(flow.as(username)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(response).get("count").asInt();
    }

    private String mine() {
        return "$[?(@.eventName == '" + eventName + "')]";
    }

    private String assignment(boolean reassignment) {
        return "$[?(@.eventName == '%s' && @.type == 'coordinator_assignment' && @.isReassignment == %s)]"
                .formatted(eventName, reassignment);
    }

    private String statusChange(String newStatus) {
        return "$[?(@.eventName == '%s' && @.type == 'status_change' && @.newStatus == '%s')]"
                .formatted(eventName, newStatus);
    }

    private int saved(String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE event_name = ? AND type = ?::notification_type",
                Integer.class, eventName, type);
    }

    private int savedStatusChange(String newStatus) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE event_name = ? AND type = 'status_change' AND new_status = ?
                """, Integer.class, eventName, newStatus);
    }

    private UUID assignmentNotificationId() {
        return jdbc.queryForObject(
                "SELECT notification_id FROM notifications WHERE event_name = ? AND type = 'coordinator_assignment'",
                UUID.class, eventName);
    }

    private String requestStatus(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM event_requests WHERE request_id = ?", String.class, requestId);
    }

    private UUID coordinatorOf(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT coordinator_id FROM event_requests WHERE request_id = ?", UUID.class, requestId);
    }

    private String eventStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void assigningACoordinatorNotifiesTheOrganiserWithTheCoordinatorsNameAndContact() throws Exception {
        UUID requestId = assignedRequest();

        assertThat(saved("coordinator_assignment")).isEqualTo(1);
        notificationsOf("eo1")
                .andExpect(jsonPath(assignment(false) + ".eventRequestId", contains(requestId.toString())))
                .andExpect(jsonPath(assignment(false) + ".eventName", contains(eventName)))
                .andExpect(jsonPath(assignment(false) + ".coordinatorName", contains("ec1")))
                .andExpect(jsonPath(assignment(false) + ".coordinatorEmail", contains("ec1@connectsphere.test")))
                .andExpect(jsonPath(assignment(false) + ".occurredAt", contains(notNullValue())))
                .andExpect(jsonPath(assignment(false) + ".read", contains(false)))
                .andExpect(jsonPath(assignment(false) + ".linkPath", contains("/organiser/requests/" + requestId)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ec1", "ec2"})
    void assigningARequestThatAlreadyHasACoordinatorIsRefusedAndCreatesNoNotification(String coordinator)
            throws Exception {
        UUID requestId = assignedRequest();

        assign(requestId, coordinator).andExpect(status().isConflict());

        assertThat(saved("coordinator_assignment")).isEqualTo(1);
        assertThat(coordinatorOf(requestId)).isEqualTo(flow.idOf("ec1"));
        notificationsOf("eo1")
                .andExpect(jsonPath(assignment(true), empty()))
                .andExpect(jsonPath(assignment(false) + ".coordinatorName", contains("ec1")));
    }

    @Test
    void approvingNotifiesTheOrganiserWithTheNewStatusAndALinkToTheEvent() throws Exception {
        UUID requestId = assignedRequest();

        UUID eventId = approve(requestId);

        assertThat(requestStatus(requestId)).isEqualTo("approved");
        assertThat(savedStatusChange("approved")).isEqualTo(1);
        notificationsOf("eo1")
                .andExpect(jsonPath(statusChange("approved") + ".eventRequestId", contains(requestId.toString())))
                .andExpect(jsonPath(statusChange("approved") + ".eventId", contains(eventId.toString())))
                .andExpect(jsonPath(statusChange("approved") + ".eventName", contains(eventName)))
                .andExpect(jsonPath(statusChange("approved") + ".occurredAt", contains(notNullValue())))
                .andExpect(jsonPath(statusChange("approved") + ".read", contains(false)))
                .andExpect(jsonPath(statusChange("approved") + ".linkPath", contains("/organiser/events/" + eventId)));
    }

    @Test
    void rejectingNotifiesTheOrganiserWithTheNewStatusAndTheReason() throws Exception {
        UUID requestId = assignedRequest();

        reject(requestId, "Venue cannot host this date").andExpect(status().isOk());

        assertThat(requestStatus(requestId)).isEqualTo("rejected");
        assertThat(savedStatusChange("rejected")).isEqualTo(1);
        notificationsOf("eo1")
                .andExpect(jsonPath(statusChange("rejected") + ".eventRequestId", contains(requestId.toString())))
                .andExpect(jsonPath(statusChange("rejected") + ".reason", contains("Venue cannot host this date")))
                .andExpect(jsonPath(statusChange("rejected") + ".linkPath", contains("/organiser/requests/" + requestId)));
    }

    @Test
    void openingTheReviewScreenCreatesNoNotification() throws Exception {
        UUID requestId = assignedRequest();

        mvc.perform(get("/api/event-requests/" + requestId + "/review").with(flow.as("ec1")))
                .andExpect(status().isOk());

        assertThat(saved("coordinator_assignment")).isEqualTo(1);
        assertThat(saved("status_change")).isZero();
    }

    @Test
    void confirmingTheEventSetsItToConfirmedAndNotifiesTheOrganiser() throws Exception {
        UUID eventId = approve(assignedRequest());

        confirm(eventId, "ec1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.status").value("confirmed"));

        assertThat(eventStatus(eventId)).isEqualTo("confirmed");
        assertThat(savedStatusChange("confirmed")).isEqualTo(1);
        notificationsOf("eo1")
                .andExpect(jsonPath(statusChange("confirmed") + ".eventId", contains(eventId.toString())))
                .andExpect(jsonPath(statusChange("confirmed") + ".eventName", contains(eventName)))
                .andExpect(jsonPath(statusChange("confirmed") + ".occurredAt", contains(notNullValue())))
                .andExpect(jsonPath(statusChange("confirmed") + ".linkPath", contains("/organiser/events/" + eventId)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "vs1", "ts1", "att1"})
    void otherRolesCannotConfirmAnEventAndNoNotificationIsCreated(String username) throws Exception {
        UUID eventId = approve(assignedRequest());

        confirm(eventId, username).andExpect(status().isForbidden());

        assertThat(eventStatus(eventId)).isEqualTo("pending");
        assertThat(savedStatusChange("confirmed")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo2", "eo3", "ec1"})
    void onlyTheOrganiserWhoCreatedTheRequestSeesItsNotifications(String username) throws Exception {
        approve(assignedRequest());

        notificationsOf(username).andExpect(jsonPath(mine(), empty()));
        notificationsOf("eo1")
                .andExpect(jsonPath(assignment(false) + ".eventName", contains(eventName)))
                .andExpect(jsonPath(statusChange("approved") + ".eventName", contains(eventName)));
    }

    @Test
    void theUnreadCountRisesWithANewNotificationAndFallsWhenItIsMarkedRead() throws Exception {
        int before = unreadCountOf("eo1");
        assignedRequest();
        assertThat(unreadCountOf("eo1")).isEqualTo(before + 1);

        mvc.perform(post("/api/notifications/" + assignmentNotificationId() + "/read").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        assertThat(unreadCountOf("eo1")).isEqualTo(before);
    }

    @Test
    void markingANotificationReadDoesNotChangeTheRequestOrItsCoordinator() throws Exception {
        UUID requestId = assignedRequest();

        mvc.perform(post("/api/notifications/" + assignmentNotificationId() + "/read").with(flow.as("eo1")))
                .andExpect(status().isOk());

        assertThat(requestStatus(requestId)).isEqualTo("pending");
        assertThat(coordinatorOf(requestId)).isEqualTo(flow.idOf("ec1"));
    }

    @Test
    void anUnauthenticatedCallerCannotReadNotifications() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
    }
}
