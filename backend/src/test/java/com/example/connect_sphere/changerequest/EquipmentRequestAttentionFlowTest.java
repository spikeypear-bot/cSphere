package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * EC-NEW3 (D37) end to end: approving an organiser's change flags the
 * event's approved equipment request 'attention' when the change breaks a
 * reservation's window or changes the equipment requirements text.
 *
 * Fixture times: FlowSupport's events run 10 Mar 2027, 09:00-12:00 Singapore
 * time (01:00-04:00Z); reservations are made for exactly that window.
 */
@Tag("integration")
@Tag("EC-NEW3")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentRequestAttentionFlowTest {

    private static final String COVERAGE = "The event's new time no longer matches an existing equipment reservation.";
    private static final String TEXT = "The event's equipment requirements text changed.";

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

    private static String details(String start, String end, String equipmentRequirements) {
        return """
                {"eventName":"Town Hall","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-10T%s:00+08:00","endDatetime":"2027-03-10T%s:00+08:00",
                 "expectedAttendance":150,"venueRequirements":"Theatre seating",%s
                 "accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(start, end,
                equipmentRequirements == null ? "" : "\"equipmentRequirements\":\"" + equipmentRequirements + "\",");
    }

    private UUID event() throws Exception {
        return flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
    }

    private UUID equipmentRequest(UUID eventId, boolean approved) throws Exception {
        UUID projector = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'D37 Projector', 10, false, 'A')
                """, projector);
        String body = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Two screens\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":2}]}"
                                .formatted(projector)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(flow.read(body).get("requestId").asString());
        sync();
        if (approved) {
            mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":2,".formatted(eventId, projector)
                                    + "\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                    .andExpect(status().isCreated());
            mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                    .andExpect(status().isOk());
        }
        sync();
        return requestId;
    }

    /** The organiser asks for {@code details}; the coordinator approves, acknowledging every attention item. */
    private void changeApproved(UUID eventId, String details) throws Exception {
        sync();
        UUID requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
        String asked = mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"reason\":\"Plans changed\",\"details\":" + details + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        UUID changeId = UUID.fromString(flow.read(asked).get("changeRequestId").asString());
        List<String> keys = new ArrayList<>();
        flow.read(mvc.perform(get("/api/change-requests/" + changeId).with(flow.as("ec1")))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("impact").forEach(i -> {
                    if ("attention".equals(i.get("status").asString())) keys.add("\"" + i.get("key").asString() + "\"");
                });
        mvc.perform(post("/api/change-requests/" + changeId + "/approve").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[" + String.join(",", keys) + "]}"))
                .andExpect(status().isOk());
        sync();
    }

    private String statusOf(UUID requestId) {
        return jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id = ?", String.class, requestId);
    }

    private String reasonOf(UUID requestId) {
        return jdbc.queryForObject("SELECT attention_reason FROM equipment_requests WHERE request_id = ?", String.class, requestId);
    }

    private int attentionNotices(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE payload->>'event_id' = ? AND payload->>'type' = 'equipment_request_attention'
                  AND payload->>'recipient_user_id' = ?
                """, Integer.class, eventId.toString(), flow.idOf("ec1").toString());
    }

    private List<String> attentionEntries(UUID eventId) {
        return jdbc.queryForList("""
                SELECT message FROM event_request_activity
                WHERE event_id = ? AND activity_type = 'equipment_request_attention'
                """, String.class, eventId);
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (happy): a new time outside the reservation flags the approved request, logs it and tells the coordinator")
    void aNewTimeOutsideTheReservationFlagsTheApprovedRequest() throws Exception {
        UUID eventId = event();
        UUID requestId = equipmentRequest(eventId, true);

        changeApproved(eventId, details("13:00", "16:00", null));

        assertThat(statusOf(requestId)).isEqualTo("attention");
        assertThat(reasonOf(requestId)).isEqualTo(COVERAGE);
        assertThat(attentionEntries(eventId)).containsExactly(COVERAGE);
        assertThat(attentionNotices(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (happy): a change to the equipment requirements text flags the approved request")
    void aChangedEquipmentRequirementsTextFlagsTheApprovedRequest() throws Exception {
        UUID eventId = event();
        UUID requestId = equipmentRequest(eventId, true);

        changeApproved(eventId, details("09:00", "12:00", "Two more microphones"));

        assertThat(statusOf(requestId)).isEqualTo("attention");
        assertThat(reasonOf(requestId)).isEqualTo(TEXT);
        assertThat(attentionNotices(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (boundary): a new time still exactly inside the reservation leaves the request approved")
    void aNewTimeExactlyInsideTheReservationLeavesItApproved() throws Exception {
        UUID eventId = event();
        UUID requestId = equipmentRequest(eventId, true);

        changeApproved(eventId, details("09:30", "12:00", null));

        assertThat(statusOf(requestId)).isEqualTo("approved");
        assertThat(reasonOf(requestId)).isNull();
        assertThat(attentionNotices(eventId)).isZero();
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (negative): a request still awaiting Technical Support is not flagged and nobody is told")
    void aProcessingRequestIsNotFlagged() throws Exception {
        UUID eventId = event();
        UUID requestId = equipmentRequest(eventId, false);

        changeApproved(eventId, details("13:00", "16:00", "Two more microphones"));

        assertThat(statusOf(requestId)).isEqualTo("processing");
        assertThat(attentionNotices(eventId)).isZero();
        assertThat(attentionEntries(eventId)).isEmpty();
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (negative): a change touching neither the times nor the equipment requirements leaves it approved")
    void anUnrelatedChangeLeavesItApproved() throws Exception {
        UUID eventId = event();
        UUID requestId = equipmentRequest(eventId, true);

        changeApproved(eventId, details("09:00", "12:00", null).replace("All hands", "All hands, with Q&A"));

        assertThat(statusOf(requestId)).isEqualTo("approved");
        assertThat(attentionNotices(eventId)).isZero();
    }
}
