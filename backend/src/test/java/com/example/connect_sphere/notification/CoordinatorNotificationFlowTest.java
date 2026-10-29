package com.example.connect_sphere.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * EC-NEW4 through the real API, services and PostgreSQL. Notifications are
 * queued in the notification outbox inside the action's own transaction and
 * delivered after it commits; these tests read that queue, so they see
 * exactly what each person will receive, and that a refused action queues
 * nothing. Every test rolls back.
 */
@Tag("integration")
@Tag("EC-NEW4")
@Tag("ELC-C6")
@Tag("ELC-C5")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CoordinatorNotificationFlowTest {

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;

    private FlowSupport flow;
    private String name;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        name = "EC-NEW4 Gala " + UUID.randomUUID();
    }

    /** What is queued for one person about this test's event, oldest first. */
    private List<Map<String, Object>> queuedFor(String username) {
        entityManager.flush();
        return jdbc.queryForList("""
                SELECT payload->>'type' AS type, payload->>'message' AS message, payload->>'actor_name' AS actor,
                       payload->>'actor_role' AS role, payload->>'reason' AS reason
                FROM notification_outbox
                WHERE payload->>'event_name' = ? AND (payload->>'recipient_user_id')::uuid = ?
                ORDER BY created_at""", name, flow.idOf(username));
    }

    private List<String> typesFor(String username) {
        return queuedFor(username).stream().map(r -> (String) r.get("type")).toList();
    }

    private Map<String, Object> only(String username, String type) {
        List<Map<String, Object>> rows = queuedFor(username).stream().filter(r -> type.equals(r.get("type"))).toList();
        assertThat(rows).as(username + " " + type).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("EC-NEW4 AC16, AC17 (happy): a new submission tells the Lead it is waiting, with the organisation, dates (Singapore time) and who sent it; no coordinator is told")
    void aNewSubmissionTellsTheLead() throws Exception {
        flow.submitted("eo1", FlowSupport.requestBody(name, 150, "none"));

        Map<String, Object> notice = only("ecl1", "request_submitted");
        assertThat((String) notice.get("message")).isEqualTo("Acme Pte Ltd submitted a request for 10 Mar 2027, 9:00 AM to "
                + "10 Mar 2027, 12:00 PM (Singapore time), 150 expected. It is waiting to be assigned.");
        assertThat(notice.get("actor")).isEqualTo("eo1");
        assertThat(notice.get("role")).isEqualTo("eo");
        assertThat(typesFor("ec1")).isEmpty();
    }

    @Test
    @DisplayName("EC-NEW4 AC2, AC17 (happy): the Lead assigning a request tells the coordinator, with the organisation and requested dates, and that the Lead did it")
    void assigningTellsTheCoordinator() throws Exception {
        flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));

        Map<String, Object> notice = only("ec1", "request_assigned");
        assertThat((String) notice.get("message")).isEqualTo("Assigned to you: Acme Pte Ltd, 10 Mar 2027, 9:00 AM to "
                + "10 Mar 2027, 12:00 PM (Singapore time), 150 expected.");
        assertThat(notice.get("actor")).isEqualTo("ecl1");
        assertThat(notice.get("role")).isEqualTo("ecl");
        // The organiser still hears who their coordinator is (EO19).
        assertThat(typesFor("eo1")).contains("coordinator_assignment");
        assertThat(typesFor("ec2")).isEmpty();
    }

    @Test
    @DisplayName("EC-NEW4 AC3, AC4 (conflict): transferring tells the new coordinator who had it, and gives the previous one a final handoff")
    void transferringTellsBothCoordinators() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));

        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());

        assertThat((String) only("ec2", "request_assigned").get("message")).startsWith("Transferred to you from ec1: Acme Pte Ltd");
        assertThat((String) only("ec1", "request_handed_over").get("message"))
                .isEqualTo("Reassigned to ec2. You no longer coordinate it, and will not be told about it any more.");
    }

    @Test
    @DisplayName("EC-NEW4 AC29 (failure): an action that is refused queues no notification for anyone")
    void aRefusedActionTellsNobody() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody(name, 150, "none")
                .replace("2027-03-10T12:00:00+08:00", "2027-03-10T09:00:00+08:00"));

        mvc.perform(post("/api/event-requests/" + draft + "/submit").with(flow.as("eo1")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(typesFor("ecl1")).isEmpty();
    }

    @Test
    @DisplayName("EC-NEW4 AC11 (happy): Technical Support rejecting an equipment request tells the coordinator, with the reason")
    void anEquipmentDecisionTellsTheCoordinator() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
        UUID mics = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type) "
                + "VALUES (?, ?, 10, false, 'A')", mics, "Mic " + mics);
        String created = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage audio\",\"items\":[{\"equipmentId\":\"" + mics + "\",\"quantity\":4}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        entityManager.flush();
        entityManager.clear();

        mvc.perform(patch("/api/equipment-requests/" + flow.read(created).get("requestId").asString() + "/status")
                        .with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"rejected\",\"rejectReason\":\"All mics are booked\"}"))
                .andExpect(status().isOk());

        Map<String, Object> notice = only("ec1", "equipment_request_decided");
        assertThat(notice.get("message")).isEqualTo("Technical Support rejected your equipment request: All mics are booked");
        assertThat(notice.get("reason")).isEqualTo("All mics are booked");
        assertThat(notice.get("role")).isEqualTo("technician");
    }

    @Test
    @DisplayName("EC-NEW4 AC9 (happy): a rejected booking tells the coordinator the reason and the suggested alternative")
    void aRejectedBookingNamesTheAlternative() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
        UUID hall = venues.createVenue(new CreateVenueDto("Harbour Hall " + UUID.randomUUID(), 300, List.of(VenueLayout.theatre),
                "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        String alternative = "Marina Hall " + UUID.randomUUID();
        UUID marina = venues.createVenue(new CreateVenueDto(alternative, 300, List.of(VenueLayout.theatre),
                "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + hall + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        entityManager.flush();

        mvc.perform(patch("/api/venue-bookings/" + flow.read(booking).get("bookingId").asString() + "/reject")
                        .with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Closed for repairs\",\"alternativeVenueId\":\"" + marina + "\"}"))
                .andExpect(status().isOk());

        assertThat((String) only("ec1", "venue_booking_rejected").get("message"))
                .contains("was rejected: Closed for repairs. Suggested instead: " + alternative);
    }

    @Test
    @DisplayName("EC-NEW4 AC1, AC25, AC26, AC27 (boundary): each person sees only their own; mark all as read stops at what was seen and changes nothing else")
    void markAllAsReadStopsAtWhatWasSeen() throws Exception {
        UUID mine = flow.idOf("ec1");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
        entityManager.flush();
        for (Object[] row : new Object[][] {
                {older, mine, "2020-01-06T01:00:00Z"}, {newer, mine, "2020-01-06T03:00:00Z"},
                {theirs, flow.idOf("ec2"), "2020-01-06T01:00:00Z"}}) {
            jdbc.update("INSERT INTO notifications (notification_id, recipient_user_id, event_id, type, event_name, occurred_at) "
                    + "VALUES (?, ?, ?, 'event_safety_approved', ?, ?::timestamptz)", row[0], row[1], eventId, name, row[2]);
        }

        // ec1 saw up to the older one; the newer one arrived meanwhile.
        mvc.perform(post("/api/notifications/read-all").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upTo\":\"2020-01-06T01:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(1));

        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT read_at IS NOT NULL FROM notifications WHERE notification_id = ?", Boolean.class, older)).isTrue();
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE notification_id = ?", Boolean.class, newer)).isTrue();
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE notification_id = ?", Boolean.class, theirs)).isTrue();
        // Never another person's: not listed, and not markable.
        mvc.perform(get("/api/notifications").with(flow.as("ec1")))
                .andExpect(jsonPath("$[?(@.notificationId == '" + theirs + "')]").isEmpty());
        mvc.perform(post("/api/notifications/" + theirs + "/read").with(flow.as("ec1"))).andExpect(status().isNotFound());
    }
}
