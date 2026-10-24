package com.example.connect_sphere.equipmentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.changerequest.ChangeApproved;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestSubmitted;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

import tools.jackson.databind.JsonNode;

/**
 * TS11 against real PostgreSQL. Notices are sent after the coordinator's
 * action commits, so this test really commits (like NotificationFlowTest) and
 * removes everything it created afterwards.
 */
@Tag("integration")
@Tag("TS11")
@Tag("EC07")
@Tag("EC-NEW3")
@SpringBootTest
@AutoConfigureMockMvc
class TechnicalSupportNotificationFlowTest {

    /** FlowSupport.requestBody's event: 10 Mar 2027, 09:00-12:00 Singapore time. */
    private static final String START = "2027-03-10T01:00:00Z";
    private static final String END = "2027-03-10T04:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TechnicalSupportNotificationService notifier;

    private FlowSupport flow;
    private String eventName;
    private final List<UUID> equipmentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        eventName = "TS11 Gala " + UUID.randomUUID();
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE event_request_activity DISABLE TRIGGER trg_activity_append_only");
            String events = "(SELECT event_id FROM events WHERE event_name = ?)";
            String requests = "(SELECT request_id FROM event_requests WHERE event_name = ?)";
            jdbc.update("DELETE FROM notifications WHERE event_name = ?", eventName);
            jdbc.update("DELETE FROM equipment_change_reviews WHERE event_id IN " + events, eventName);
            jdbc.update("DELETE FROM equipment_logs WHERE event_id IN " + events, eventName);
            jdbc.update("DELETE FROM equipment_request_equipments WHERE request_id IN "
                    + "(SELECT request_id FROM equipment_requests WHERE event_id IN " + events + ")", eventName);
            jdbc.update("DELETE FROM equipment_requests WHERE event_id IN " + events, eventName);
            jdbc.update("DELETE FROM event_change_requests WHERE request_id IN " + requests, eventName);
            jdbc.update("DELETE FROM event_request_activity WHERE request_id IN " + requests, eventName);
            jdbc.update("DELETE FROM event_requests WHERE event_name = ?", eventName);
            jdbc.update("DELETE FROM events WHERE event_name = ?", eventName);
            for (UUID id : equipmentIds) {
                jdbc.update("DELETE FROM equipments WHERE equipment_id = ?", id);
            }
            jdbc.execute("ALTER TABLE event_request_activity ENABLE TRIGGER trg_activity_append_only");
        });
    }

    // ---- fixtures ---------------------------------------------------------------------

    private UUID equipment(String name, int quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)"
                + " VALUES (?, ?, ?, false, 'E')", id, name, quantity);
        equipmentIds.add(id);
        return id;
    }

    private UUID event() throws Exception {
        return flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(eventName, 150, "none"));
    }

    private ResultActions requestEquipment(UUID eventId, UUID equipmentId, int quantity) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"technicalRequirement\":\"Stage left\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":%d}]}"
                        .formatted(equipmentId, quantity)));
    }

    private UUID requested(UUID eventId, UUID equipmentId, int quantity) throws Exception {
        String body = requestEquipment(eventId, equipmentId, quantity).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(body).get("requestId").asString());
    }

    /** Reserved by Technical Support for the event's own time and approved (TS02, TS06). */
    private UUID reservedAndApproved(UUID eventId, UUID equipmentId, int quantity) throws Exception {
        UUID requestId = requested(eventId, equipmentId, quantity);
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,\"serialNumber\":null,\"start\":\"%s\",\"end\":\"%s\"}"
                        .formatted(eventId, equipmentId, quantity, START, END)))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk());
        return requestId;
    }

    private UUID requestOf(UUID eventId) {
        return jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
    }

    /** The organiser asks for the event to run {@code start}-{@code end} (Singapore) with {@code attendance}. */
    private UUID asked(UUID eventId, String start, String end, int attendance, String equipment) throws Exception {
        String details = """
                {"eventName":"%s","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-10T%s:00+08:00","endDatetime":"2027-03-10T%s:00+08:00",
                 "expectedAttendance":%d,"venueRequirements":"Theatre seating","equipmentRequirements":%s,
                 "accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(eventName, start, end, attendance, equipment == null ? "null" : "\"" + equipment + "\"");
        String body = mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestOf(eventId) + "\",\"reason\":\"Plans changed\",\"details\":"
                                + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(body).get("changeRequestId").asString());
    }

    /** The coordinator approves, acknowledging every impact the review shows. */
    private void approved(UUID changeRequestId) throws Exception {
        JsonNode view = flow.read(mvc.perform(get("/api/change-requests/" + changeRequestId).with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        view.get("impact").forEach(i -> {
            if ("attention".equals(i.get("status").asString())) {
                keys.add("\"" + i.get("key").asString() + "\"");
            }
        });
        mvc.perform(post("/api/change-requests/" + changeRequestId + "/approve").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[" + String.join(",", keys) + "]}"))
                .andExpect(status().isOk());
    }

    private String mine(String type) {
        return "$[?(@.type == '" + type + "' && @.eventName == '" + eventName + "')]";
    }

    private int notices(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type::text = ? AND event_name = ?",
                Integer.class, type, eventName);
    }

    private int reviewsFor(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM equipment_change_reviews WHERE event_id = ?",
                Integer.class, eventId);
    }

    private UUID reviewOf(UUID eventId) {
        return jdbc.queryForObject("SELECT review_id FROM equipment_change_reviews WHERE event_id = ? "
                + "ORDER BY changed_at DESC LIMIT 1", UUID.class, eventId);
    }

    private int technicians() {
        return users.findByRole(UserRole.technician).size();
    }

    // ---- new requests (AC1, AC3, AC15, AC16) ----------------------------------------------

    @Test
    @DisplayName("TS11 AC1, AC3 (happy): every Technical Support user is told of a new equipment request with the event, its times, the coordinator and the items, and the notice opens it in the TS01 review")
    void technicalSupportAreToldOfANewRequest() throws Exception {
        UUID projector = equipment("TS11 Projector " + UUID.randomUUID(), 5);
        UUID eventId = event();

        UUID requestId = requested(eventId, projector, 2);

        String projectorName = jdbc.queryForObject("SELECT equipment_name FROM equipments WHERE equipment_id = ?",
                String.class, projector);
        mvc.perform(get("/api/notifications").with(flow.as("ts1")))
                .andExpect(jsonPath(mine("equipment_request_submitted") + ".message", Matchers.contains(
                        eventName + ", 10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM (Singapore time). "
                                + "Coordinator: ec1. Requested: 2 × " + projectorName + ". Notes: Stage left")))
                .andExpect(jsonPath(mine("equipment_request_submitted") + ".linkPath",
                        Matchers.contains("/technical-support/availability?request=" + requestId)))
                .andExpect(jsonPath(mine("equipment_request_submitted") + ".eventId", Matchers.contains(eventId.toString())));
        assertThat(notices("equipment_request_submitted")).isEqualTo(technicians());
    }

    @Test
    @DisplayName("TS11 AC16 (negative): only Technical Support are told of a new request; the coordinator, organiser and Venue Staff are not")
    void onlyTechnicalSupportAreToldOfARequest() throws Exception {
        UUID projector = equipment("TS11 Projector " + UUID.randomUUID(), 5);
        UUID eventId = event();
        requested(eventId, projector, 1);

        for (String other : List.of("ec1", "eo1", "vs1", "ecl1")) {
            mvc.perform(get("/api/notifications").with(flow.as(other)))
                    .andExpect(jsonPath(mine("equipment_request_submitted")).isEmpty());
        }
    }

    @Test
    @DisplayName("TS11 AC1 (failure): a refused equipment request tells no one")
    void aRefusedRequestTellsNoOne() throws Exception {
        UUID eventId = event();

        mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"technicalRequirement\":\"\",\"items\":[]}"))
                // EC07's own validation decides the status; TS11 only cares that nobody is told.
                .andExpect(status().is4xxClientError());

        assertThat(notices("equipment_request_submitted")).isZero();
    }

    @Test
    @DisplayName("TS11 AC15 (conflict): the same submission processed twice tells each Technical Support user once")
    void aRequestIsAnnouncedOnce() throws Exception {
        UUID projector = equipment("TS11 Projector " + UUID.randomUUID(), 5);
        UUID eventId = event();
        UUID requestId = requested(eventId, projector, 1);

        notifier.onEquipmentRequestSubmitted(new EquipmentRequestSubmitted(requestId, eventId));

        assertThat(notices("equipment_request_submitted")).isEqualTo(technicians());
    }

    // ---- approved changes (AC4-AC10, AC15) --------------------------------------------------

    @Test
    @DisplayName("TS11 AC4, AC5, AC8, AC10 (happy): an approved new time that its reservation no longer covers tells Technical Support what changed, with a link to the event's flagged reservations")
    void anApprovedNewTimeTellsTechnicalSupport() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 2);
        UUID change = asked(eventId, "10:00", "13:00", 150, null);

        // Pending: nothing yet (AC5).
        assertThat(reviewsFor(eventId)).isZero();
        approved(change);

        assertThat(reviewsFor(eventId)).isEqualTo(1);
        UUID reviewId = reviewOf(eventId);
        String micName = jdbc.queryForObject("SELECT equipment_name FROM equipments WHERE equipment_id = ?",
                String.class, mics);
        mvc.perform(get("/api/notifications").with(flow.as("ts2")))
                .andExpect(jsonPath(mine("equipment_change_review") + ".linkPath",
                        Matchers.contains("/technical-support/change-reviews/" + reviewId)))
                .andExpect(jsonPath(mine("equipment_change_review") + ".message", Matchers.contains(Matchers.allOf(
                        Matchers.startsWith("An approved change to " + eventName + " on "),
                        Matchers.containsString("Coordinator: ec1."),
                        Matchers.containsString("Start: 10 Mar 2027, 9:00 AM → 10 Mar 2027, 10:00 AM"),
                        Matchers.containsString("End: 10 Mar 2027, 12:00 PM → 10 Mar 2027, 1:00 PM"),
                        Matchers.containsString("No longer covering the event: 2 × " + micName
                                + " reserved 10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM")))));
        assertThat(notices("equipment_change_review")).isEqualTo(technicians());

        mvc.perform(get("/api/equipment-change-reviews/" + reviewId).with(flow.as("ts1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventName").value(eventName))
                .andExpect(jsonPath("$.coordinatorName").value("ec1"))
                .andExpect(jsonPath("$.needsReview").value(true))
                .andExpect(jsonPath("$.reservations[0].equipmentName").value(micName))
                .andExpect(jsonPath("$.reservations[0].flagged").value(true))
                .andExpect(jsonPath("$.affectedAtChange.length()").value(1));
    }

    @Test
    @DisplayName("TS11 AC4 (happy): a change to the equipment requirements alone is reviewed even though the reservations still cover the event")
    void changedRequirementsAreReviewed() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);

        approved(asked(eventId, "09:00", "12:00", 150, "Two extra microphones"));

        UUID reviewId = reviewOf(eventId);
        mvc.perform(get("/api/equipment-change-reviews/" + reviewId).with(flow.as("ts1")))
                .andExpect(jsonPath("$.requirementsChanged").value(true))
                .andExpect(jsonPath("$.updated.equipmentRequirements").value("Two extra microphones"))
                .andExpect(jsonPath("$.affectedAtChange").isEmpty())
                .andExpect(jsonPath("$.reservations[0].flagged").value(false));
        mvc.perform(get("/api/notifications").with(flow.as("ts1")))
                .andExpect(jsonPath(mine("equipment_change_review") + ".message", Matchers.contains(Matchers.allOf(
                        Matchers.containsString("Equipment requirements: none → Two extra microphones"),
                        Matchers.endsWith("Existing reservations still cover the event.")))));
    }

    @Test
    @DisplayName("TS11 AC5 (negative): a rejected change tells Technical Support nothing")
    void aRejectedChangeTellsNoOne() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);
        UUID change = asked(eventId, "10:00", "13:00", 150, null);

        mvc.perform(post("/api/change-requests/" + change + "/reject").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Hall is busy\"}")).andExpect(status().isOk());

        assertThat(reviewsFor(eventId)).isZero();
        assertThat(notices("equipment_change_review")).isZero();
    }

    @Test
    @DisplayName("TS11 AC6 (negative): an approved change to attendance only tells Technical Support nothing")
    void aChangeUnrelatedToEquipmentTellsNoOne() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);

        approved(asked(eventId, "09:00", "12:00", 180, null));

        assertThat(reviewsFor(eventId)).isZero();
        assertThat(notices("equipment_change_review")).isZero();
    }

    @Test
    @DisplayName("TS11 AC7 (negative): an event with no equipment request, or only a rejected one, is not reviewed when its time changes")
    void eventsWithoutALiveRequestAreSkipped() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();

        approved(asked(eventId, "10:00", "13:00", 150, null));
        assertThat(reviewsFor(eventId)).isZero();

        UUID requestId = requested(eventId, mics, 1);
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"rejectReason\":\"None left\"}"))
                .andExpect(status().isOk());
        approved(asked(eventId, "11:00", "14:00", 150, "Microphones please"));

        assertThat(reviewsFor(eventId)).isZero();
        assertThat(notices("equipment_change_review")).isZero();
    }

    @Test
    @DisplayName("TS11 AC9, AC14, AC15 (conflict): the same approval processed again opens no second review; a later approved change opens a new one and keeps the first one's values")
    void eachApprovedChangeIsReviewedOnce() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);
        UUID first = asked(eventId, "10:00", "13:00", 150, null);
        approved(first);
        UUID firstReview = reviewOf(eventId);

        Map<String, Object> previous = Map.of("startDatetime", "2027-03-10T09:00+08:00");
        notifier.onChangeApproved(new ChangeApproved(first, eventId, previous,
                Map.of("startDatetime", "2027-03-10T10:00+08:00", "endDatetime", "2027-03-10T13:00+08:00"),
                java.util.Set.of("startDatetime", "endDatetime"), java.time.OffsetDateTime.now()));
        assertThat(reviewsFor(eventId)).isEqualTo(1);
        assertThat(notices("equipment_change_review")).isEqualTo(technicians());

        approved(asked(eventId, "11:00", "14:00", 150, null));

        assertThat(reviewsFor(eventId)).isEqualTo(2);
        // The first review still compares 09:00 with 10:00, not with the later 11:00 (AC9).
        String kept = jdbc.queryForObject("SELECT updated_values->>'startDatetime' FROM equipment_change_reviews "
                + "WHERE review_id = ?", String.class, firstReview);
        assertThat(java.time.OffsetDateTime.parse(kept).toInstant())
                .isEqualTo(java.time.Instant.parse("2027-03-10T02:00:00Z"));
    }

    // ---- review (AC11-AC14) ----------------------------------------------------------------

    @Test
    @DisplayName("TS11 AC11, AC12, AC13, AC14 (happy): Technical Support record that the change needs the coordinator; the coordinator sees it; the flag clears and nothing else changes")
    void recordingAnOutcome() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        UUID requestId = reservedAndApproved(eventId, mics, 2);
        approved(asked(eventId, "10:00", "13:00", 150, null));
        UUID reviewId = reviewOf(eventId);
        String before = jdbc.queryForObject("SELECT string_agg(log_id::text || quantity || loaned_from || loaned_until, ',')"
                + " FROM equipment_logs WHERE event_id = ?", String.class, eventId);
        String eventStatus = jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);

        // Reading the notice does not complete the review (AC13).
        UUID noticeId = jdbc.queryForObject("SELECT notification_id FROM notifications WHERE equipment_review_id = ? "
                + "AND recipient_user_id = ?", UUID.class, reviewId, flow.idOf("ts1"));
        mvc.perform(post("/api/notifications/" + noticeId + "/read").with(flow.as("ts1"))).andExpect(status().isOk());
        mvc.perform(get("/api/equipment-change-reviews").with(flow.as("ts1")))
                .andExpect(jsonPath("$[?(@.reviewId == '" + reviewId + "')].needsReview", Matchers.contains(true)));

        mvc.perform(post("/api/equipment-change-reviews/" + reviewId + "/outcome").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"needs_coordinator_action\",\"note\":\"Request two more mics for 12-1pm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsReview").value(false))
                .andExpect(jsonPath("$.outcome").value("needs_coordinator_action"))
                .andExpect(jsonPath("$.reviewedByName").value("ts1"))
                .andExpect(jsonPath("$.reservations[0].flagged").value(false));

        mvc.perform(get("/api/events/" + eventId + "/equipment-change-reviews").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("needs_coordinator_action"))
                .andExpect(jsonPath("$[0].outcomeNote").value("Request two more mics for 12-1pm"))
                .andExpect(jsonPath("$[0].needsReview").value(false));
        mvc.perform(get("/api/events/" + eventId + "/equipment-change-reviews").with(flow.as("ecl1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT string_agg(log_id::text || quantity || loaned_from || loaned_until, ',')"
                + " FROM equipment_logs WHERE event_id = ?", String.class, eventId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id = ?",
                String.class, requestId)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId))
                .isEqualTo(eventStatus);

        // Recorded once (a second outcome is refused and changes nothing).
        mvc.perform(post("/api/equipment-change-reviews/" + reviewId + "/outcome").with(flow.as("ts2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"still_suitable\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM equipment_change_reviews WHERE review_id = ?",
                UUID.class, reviewId)).isEqualTo(flow.idOf("ts1"));
    }

    @Test
    @DisplayName("TS11 AC11 (negative): needing the coordinator without a note is refused and the event stays flagged")
    void aNoteIsRequiredForCoordinatorAction() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);
        approved(asked(eventId, "10:00", "13:00", 150, null));
        UUID reviewId = reviewOf(eventId);

        mvc.perform(post("/api/equipment-change-reviews/" + reviewId + "/outcome").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"needs_coordinator_action\",\"note\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT outcome IS NULL FROM equipment_change_reviews WHERE review_id = ?",
                Boolean.class, reviewId)).isTrue();
    }

    // ---- access (AC16) -----------------------------------------------------------------------

    @Test
    @DisplayName("TS11 AC16 (negative): only Technical Support reach the reviews; another coordinator cannot read an event's outcomes")
    void reviewsAreTechnicalSupportOnly() throws Exception {
        UUID mics = equipment("TS11 Mic " + UUID.randomUUID(), 5);
        UUID eventId = event();
        reservedAndApproved(eventId, mics, 1);
        approved(asked(eventId, "10:00", "13:00", 150, null));
        UUID reviewId = reviewOf(eventId);

        for (String other : List.of("ec1", "eo1", "vs1", "ecl1", "att1")) {
            mvc.perform(get("/api/equipment-change-reviews").with(flow.as(other))).andExpect(status().isForbidden());
            mvc.perform(get("/api/equipment-change-reviews/" + reviewId).with(flow.as(other)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/equipment-change-reviews/" + reviewId + "/outcome").with(flow.as(other))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"still_suitable\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/notifications").with(flow.as(other)))
                    .andExpect(jsonPath(mine("equipment_change_review")).isEmpty());
        }
        for (String other : List.of("eo1", "vs1", "ts1")) {
            mvc.perform(get("/api/events/" + eventId + "/equipment-change-reviews").with(flow.as(other)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/events/" + eventId + "/equipment-change-reviews").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT outcome IS NULL FROM equipment_change_reviews WHERE review_id = ?",
                Boolean.class, reviewId)).isTrue();
    }
}
