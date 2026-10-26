package com.example.connect_sphere.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;
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
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

@Tag("integration") @Tag("VS17") @Tag("SO-C2")
@Tag("EO09")
@Tag("EO19")
@SpringBootTest
@AutoConfigureMockMvc
@Tag("EC08")
@Tag("SO-C4")
class NotificationFlowTest {
    @Autowired com.example.connect_sphere.safety.ReadinessService venueReadiness;

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired VenueService venues;

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
            jdbc.update("""
                    DELETE FROM venue_bookings
                    WHERE event_id IN (SELECT event_id FROM events WHERE event_name = ?)
                    """, eventName);
            jdbc.update("DELETE FROM events WHERE event_name = ?", eventName);
            jdbc.update("DELETE FROM venues WHERE venue_address = ?", eventName);
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

    private ResultActions reassign(UUID requestId, String coordinator) throws Exception {
        return mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
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

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    /** EC03/VS02: an approved venue, which EC08 needs before the safety check. */
    private void bookAndApproveVenue(UUID eventId) throws Exception {
        UUID venueId = venues.createVenue(new CreateVenueDto(eventName, 200, List.of(VenueLayout.theatre),
                "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(patch("/api/venue-bookings/" + flow.read(booking).get("bookingId").asString() + "/approve")
                        .with(flow.as("vs1")))
                .andExpect(status().isOk());
    }

    private void completeReadiness(UUID eventId) throws Exception {
        for (String itemKey : venueReadiness.forSafetyOfficer(eventId).items().stream().map(com.example.connect_sphere.safety.ReadinessItemDto::itemKey).toList()) {
            mvc.perform(patch("/api/safety/events/" + eventId + "/readiness/items/" + itemKey)
                    .with(flow.as("so1"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"checked\":true,\"note\":\"Ready\"}"))
                    .andExpect(status().isOk());
        }
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
    void reassigningNotifiesTheOrganiserOfTheirNewCoordinatorAsAReassignment() throws Exception {
        UUID requestId = assignedRequest();

        reassign(requestId, "ec2").andExpect(status().isOk());

        // The first assignment's notice, and one more for the reassignment.
        assertThat(saved("coordinator_assignment")).isEqualTo(2);
        notificationsOf("eo1")
                .andExpect(jsonPath(assignment(true) + ".eventRequestId", contains(requestId.toString())))
                .andExpect(jsonPath(assignment(true) + ".coordinatorName", contains("ec2")))
                .andExpect(jsonPath(assignment(true) + ".coordinatorEmail", contains("ec2@connectsphere.test")))
                .andExpect(jsonPath(assignment(true) + ".read", contains(false)))
                .andExpect(jsonPath(assignment(true) + ".linkPath", contains("/organiser/requests/" + requestId)))
                .andExpect(jsonPath(assignment(false) + ".coordinatorName", contains("ec1")));
    }

    @Test
    void reassigningNotifiesNeitherCoordinatorNorTheLead() throws Exception {
        UUID requestId = assignedRequest();

        reassign(requestId, "ec2").andExpect(status().isOk());

        // Telling the coordinators is a separate story (EC-NEW4).
        for (String staff : List.of("ec1", "ec2", "ecl1")) {
            notificationsOf(staff).andExpect(jsonPath(mine(), empty()));
        }
    }

    @Test
    void aReassignmentToTheSameCoordinatorIsRefusedAndCreatesNoNotification() throws Exception {
        UUID requestId = assignedRequest();

        reassign(requestId, "ec1").andExpect(status().isUnprocessableEntity());

        assertThat(saved("coordinator_assignment")).isEqualTo(1);
        assertThat(coordinatorOf(requestId)).isEqualTo(flow.idOf("ec1"));
        notificationsOf("eo1").andExpect(jsonPath(assignment(true), empty()));
    }

    @Test
    void aReassignmentAfterTheCoordinatorDecidedIsRefusedAndCreatesNoNotification() throws Exception {
        UUID requestId = assignedRequest();
        reject(requestId, "No capacity").andExpect(status().isOk());

        reassign(requestId, "ec2").andExpect(status().isConflict());

        assertThat(saved("coordinator_assignment")).isEqualTo(1);
        assertThat(coordinatorOf(requestId)).isEqualTo(flow.idOf("ec1"));
        assertThat(requestStatus(requestId)).isEqualTo("rejected");
        notificationsOf("eo1").andExpect(jsonPath(assignment(true), empty()));
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

    /**
     * SO-C4 + EC08, end to end: the coordinator sends the event for its
     * safety check, the Safety Officer ticks the checklist and approves, the
     * coordinator is told and confirms, and only then is the organiser told
     * the event is confirmed.
     */
    @Test
    void safetyApprovalThenCoordinatorConfirmationNotifiesTheOrganiser() throws Exception {
        UUID eventId = approve(assignedRequest());
        bookAndApproveVenue(eventId);
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1")))
                .andExpect(status().isOk());
        completeReadiness(eventId);

        mvc.perform(post("/api/safety/events/" + eventId + "/approval").with(flow.as("so1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\",\"note\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("approved"));
        assertThat(eventStatus(eventId)).isEqualTo("safety_approved");
        assertThat(saved("event_safety_approved")).isEqualTo(1);
        assertThat(savedStatusChange("confirmed")).isZero();

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

    /**
     * Week 7 Change #6 (EC08): an event is confirmed only after the Safety
     * Officer approves it, so confirming straight from Planning is refused
     * (even with the checklist ticked) and nobody is told it is confirmed.
     */
    @Test
    void confirmingStraightFromPlanningIsRefusedAndNotifiesNobody() throws Exception {
        UUID eventId = approve(assignedRequest());
        completeReadiness(eventId);

        confirm(eventId, "ec1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "An event can only be confirmed after the Safety Officer approves it (this one is Planning)."));
        // The Safety Officer cannot decide before the coordinator sends it.
        mvc.perform(post("/api/safety/events/" + eventId + "/approval").with(flow.as("so1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(eventStatus(eventId)).isEqualTo("pending");
        assertThat(savedStatusChange("confirmed")).isZero();
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
