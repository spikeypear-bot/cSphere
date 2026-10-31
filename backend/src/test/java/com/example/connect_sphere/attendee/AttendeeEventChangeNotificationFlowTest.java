package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.persistence.EntityManager;

import com.example.connect_sphere.event.service.EventRegistrationService;
import com.example.connect_sphere.notification.entity.NotificationChange;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@SpringBootTest(properties = "notifications.retry-delay-ms=3600000")
@AutoConfigureMockMvc
@Tag("integration")
@Tag("AT09")
@Transactional
class AttendeeEventChangeNotificationFlowTest {
    private static final String ORIGINAL_START = "2027-03-10T09:00:00+08:00";
    private static final String ORIGINAL_END = "2027-03-10T12:00:00+08:00";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired AttendeeRegistrationRepository registrations;
    @Autowired NotificationService notifications;
    @Autowired EventRegistrationService eventRegistrations;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    @Test
    @DisplayName("AT09 AC1, AC2, AC9, AC11, AC12, AC13, AC20, AC21, AC25, AC26 (happy): one immutable notice per active attendee is queued only for committed important changes")
    void approvedImportantChangesAreConsolidatedAndDeduplicated() throws Exception {
        UUID eventId = event("AT09 important change");
        UUID requestId = requestId(eventId);
        register(eventId, "att1", "confirmed");
        register(eventId, "att2", "confirmed");
        register(eventId, "att3", "withdrawn");
        register(eventId, "att4", "event_cancelled");

        UUID nameOnlyChange = ask(requestId, details("AT09 renamed event", "09:00", "12:00",
                150, "Theatre seating", null));
        approve(nameOnlyChange).andExpect(status().isOk());
        assertThat(attendeeChangeCount(eventId)).isZero();

        UUID changeId = ask(requestId, details("AT09 renamed event", "10:00", "13:00",
                180, "Accessible auditorium", "Join online at the event link."));
        assertThat(attendeeChangeCount(eventId)).isZero();
        approve(changeId).andExpect(status().isOk());

        List<Map<String, Object>> notices = attendeeChangeRows(eventId);
        assertThat(notices).hasSize(2);
        assertThat(notices).extracting(row -> row.get("recipient_user_id"))
                .containsExactlyInAnyOrder(flow.idOf("att1").toString(), flow.idOf("att2").toString());
        assertThat(notices).extracting(row -> row.get("event_name")).containsOnly("AT09 renamed event");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'attendee_event_changed'
                  AND payload->'notification_changes'->0->>'previousValue' = '10 Mar 2027, 9:00 AM'
                  AND payload->'notification_changes'->0->>'updatedValue' = '10 Mar 2027, 10:00 AM'
                  AND payload->'notification_changes'->1->>'previousValue' = '10 Mar 2027, 12:00 PM'
                  AND payload->'notification_changes'->1->>'updatedValue' = '10 Mar 2027, 1:00 PM'
                  AND payload->'notification_changes'->2->>'updatedValue' = 'Join online at the event link.'
                  AND jsonb_array_length(payload->'notification_changes') = 3
                """, Long.class, eventId.toString())).isEqualTo(2);
        // AT09 AC2: attendance and venue requirements also changed, but they are
        // planning details for staff, so attendees are not shown them.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox o, jsonb_array_elements(o.payload->'notification_changes') c
                WHERE o.payload->>'event_id' = ? AND o.payload->>'type' = 'attendee_event_changed'
                  AND c->>'field' IN ('expectedAttendance', 'venueRequirements', 'equipmentRequirements')
                """, Long.class, eventId.toString())).isZero();
        assertThat(notices).allSatisfy(row -> {
            assertThat(row.get("registration_id")).isNotNull();
            assertThat(row.get("message")).isEqualTo("Important event arrangements have changed.");
        });

        OffsetDateTime committedAt = jdbc.queryForObject(
                "SELECT decided_at FROM event_change_requests WHERE change_request_id = ?",
                OffsetDateTime.class, changeId);
        notifications.createAttendeeEventChangeNotifications(changeId, eventId, "AT09 renamed event",
                List.of(new NotificationChange("startDatetime", "Start", "10 Mar 2027, 9:00 AM",
                        "10 Mar 2027, 10:00 AM")), committedAt);
        assertThat(attendeeChangeCount(eventId)).isEqualTo(2);

        UUID laterChange = ask(requestId, details("AT09 renamed event", "10:00", "13:00",
                180, "Accessible auditorium", "Use the updated joining link."));
        approve(laterChange).andExpect(status().isOk());
        assertThat(attendeeChangeCount(eventId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'attendee_event_changed'
                  AND payload->'notification_changes'->0->>'updatedValue' = 'Use the updated joining link.'
                """, Long.class, eventId.toString())).isEqualTo(2);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
        mvc.perform(get("/api/attendee/registrations/" + registrationId(eventId, "att1"))
                        .with(flow.as("att1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDatetime").value("2027-03-10T02:00:00Z"))
                .andExpect(jsonPath("$.eventName").value("AT09 renamed event"));
    }

    @Test
    @DisplayName("AT09 AC3, AC19 (negative): pending, withdrawn, and rejected changes queue no attendee notice")
    void undecidedWithdrawnAndRejectedChangesDoNotNotifyAttendees() throws Exception {
        UUID eventId = event("AT09 uncommitted change");
        UUID requestId = requestId(eventId);
        register(eventId, "att1", "confirmed");

        UUID withdrawn = ask(requestId, details("AT09 uncommitted change", "10:00", "13:00",
                180, "Accessible auditorium", null));
        assertThat(attendeeChangeCount(eventId)).isZero();
        mvc.perform(post("/api/change-requests/" + withdrawn + "/withdraw").with(flow.as("eo1")))
                .andExpect(status().isOk());
        assertThat(attendeeChangeCount(eventId)).isZero();

        UUID rejected = ask(requestId, details("AT09 uncommitted change", "10:00", "13:00",
                180, "Accessible auditorium", null));
        mvc.perform(post("/api/change-requests/" + rejected + "/reject").with(flow.as("ec1"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The venue cannot support this.\"}"))
                .andExpect(status().isOk());
        assertThat(attendeeChangeCount(eventId)).isZero();
    }

    @Test
    @DisplayName("AT09 AC5 (negative): an event that has already started cannot be approved or generate a change notice")
    void startedEventsDoNotGenerateChangeNotifications() throws Exception {
        UUID eventId = event("AT09 started event");
        UUID requestId = requestId(eventId);
        register(eventId, "att1", "confirmed");
        UUID changeId = ask(requestId, details("AT09 started event", "10:00", "13:00",
                180, "Accessible auditorium", null));

        entityManager.flush();
        entityManager.clear();
        jdbc.update("UPDATE events SET start_datetime = now() - interval '1 minute', "
                + "end_datetime = now() + interval '1 hour' WHERE event_id = ?", eventId);
        entityManager.clear();
        mvc.perform(post("/api/change-requests/" + changeId + "/approve").with(flow.as("ec1"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"acknowledged\":[]}"))
                .andExpect(status().isConflict());
        assertThat(attendeeChangeCount(eventId)).isZero();
    }

    @Test
    @DisplayName("AT09 AC4, AC6, AC7, AC10, AC14, AC26 (happy): cancellation queues private notices with original time and releases active registration count")
    void cancellationNotifiesOnlyActiveAttendeesAndSnapshotsOriginalTime() throws Exception {
        UUID eventId = event("AT09 cancelled event");
        UUID requestId = requestId(eventId);
        UUID activeRegistration = register(eventId, "att1", "confirmed");
        register(eventId, "att2", "confirmed");
        register(eventId, "att3", "withdrawn");
        register(eventId, "att4", "event_cancelled");

        String cancellation = mvc.perform(post("/api/event-requests/" + requestId + "/cancellation-request")
                        .with(flow.as("eo1"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The organiser cancelled the event.\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID cancellationId = UUID.fromString(flow.read(cancellation).get("cancellationRequestId").asString());

        mvc.perform(post("/api/events/" + eventId + "/cancellation-requests/" + cancellationId + "/approve")
                        .with(flow.as("ec1"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> notices = attendeeCancellationRows(eventId);
        assertThat(notices).hasSize(2);
        assertThat(notices).extracting(row -> row.get("recipient_user_id"))
                .containsExactlyInAnyOrder(flow.idOf("att1").toString(), flow.idOf("att2").toString());
        assertThat(notices).allSatisfy(row -> {
            assertThat(row.get("reason")).isEqualTo("The organiser cancelled the event.");
            assertThat(row.get("message")).isEqualTo("This event has been cancelled.");
                assertThat(row.get("original_event_start_datetime").toString()).contains("2027-03-10T01:00");
                assertThat(row.get("original_event_end_datetime").toString()).contains("2027-03-10T04:00");

        });

        mvc.perform(get("/api/attendee/registrations/" + activeRegistration).with(flow.as("att1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("event_cancelled"))
                .andExpect(jsonPath("$.eventStatus").value("cancelled"))
                .andExpect(jsonPath("$.startDatetime").value("2027-03-10T01:00:00Z"))
                .andExpect(jsonPath("$.endDatetime").value("2027-03-10T04:00:00Z"));
        assertThat(eventRegistrations.forOrganiser("Acme Pte Ltd", eventId).confirmedCount()).isZero();
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
    }

    private UUID event(String name) throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
        entityManager.flush();
        entityManager.clear();
        jdbc.update("""
                UPDATE events SET status = 'confirmed', confirmed_at = now(), first_confirmed_at = now(),
                    registration_needs = true, registration_capacity = 5,
                    registration_opens_at = now() - interval '1 day',
                    registration_closes_at = start_datetime
                WHERE event_id = ?
                """, eventId);
        entityManager.clear();
        return eventId;
    }

    private UUID requestId(UUID eventId) {
        return jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
    }

    private UUID register(UUID eventId, String username, String status) {
        var attendee = users.findByUsername(username).orElseThrow();
        AttendeeRegistration registration = new AttendeeRegistration();
        registration.setRegistrationId(UUID.randomUUID());
        registration.setRegistrationReference("CS-" + UUID.randomUUID().toString().substring(0, 12));
        registration.setEventId(eventId);
        registration.setAttendeeId(attendee.getUserId());
        registration.setAttendeeName(attendee.getUsername());
        registration.setAttendeeEmail(attendee.getEmail());
        registration.setStatus(status);
        registration.setRegisteredAt(OffsetDateTime.now());
        return registrations.saveAndFlush(registration).getRegistrationId();
    }

    private UUID registrationId(UUID eventId, String username) {
        return jdbc.queryForObject("""
                SELECT registration_id FROM event_registrations
                WHERE event_id = ? AND attendee_id = (SELECT user_id FROM users WHERE username = ?)
                  AND status = 'confirmed'
                """, UUID.class, eventId, username);
    }

    private UUID ask(UUID requestId, String details) throws Exception {
        String response = mvc.perform(post("/api/change-requests").with(flow.as("eo1"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"reason\":\"Plans changed\","
                                + "\"details\":" + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(response).get("changeRequestId").asString());
    }

    private org.springframework.test.web.servlet.ResultActions approve(UUID changeId) throws Exception {
        String view = mvc.perform(get("/api/change-requests/" + changeId).with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> acknowledged = new ArrayList<>();
        flow.read(view).get("impact").forEach(item -> {
            if ("attention".equals(item.get("status").asString())) {
                acknowledged.add(item.get("key").asString());
            }
        });
        String keys = String.join(",", acknowledged.stream().map(key -> "\"" + key + "\"").toList());
        return mvc.perform(post("/api/change-requests/" + changeId + "/approve").with(flow.as("ec1"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"acknowledged\":[" + keys + "]}"));
    }

    private static String details(String name, String start, String end, int attendance, String venue, String instructions) {
        String joining = instructions == null ? "" : ",\"attendeeInstructions\":\"" + instructions + "\"";
        return """
                {"eventName":"%s","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-10T%s:00+08:00","endDatetime":"2027-03-10T%s:00+08:00",
                 "expectedAttendance":%d,"venueRequirements":"%s",
                 "accessibilityNeeds":["none"],"requiredFacilities":[],"registrationNeeds":false%s}
                """.formatted(name, start, end, attendance, venue, joining);
    }

    private long attendeeChangeCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'attendee_event_changed'
                """, Long.class, eventId.toString());
    }

    private List<Map<String, Object>> attendeeChangeRows(UUID eventId) {
        return jdbc.queryForList("""
                SELECT payload->>'recipient_user_id' AS recipient_user_id,
                       payload->>'registration_id' AS registration_id,
                       payload->>'event_name' AS event_name,
                       payload->>'message' AS message
                FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'attendee_event_changed'
                ORDER BY payload->>'recipient_user_id'
                """, eventId.toString());
    }

    private List<Map<String, Object>> attendeeCancellationRows(UUID eventId) {
        return jdbc.queryForList("""
                SELECT payload->>'recipient_user_id' AS recipient_user_id,
                       payload->>'reason' AS reason,
                       payload->>'message' AS message,
                       payload->>'original_event_start_datetime' AS original_event_start_datetime,
                       payload->>'original_event_end_datetime' AS original_event_end_datetime
                FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'attendee_event_cancelled'
                ORDER BY payload->>'recipient_user_id'
                """, eventId.toString());
    }
}
