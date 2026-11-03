package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

/** User-supplied Release 1 repair cases: real services, mapping, authorization and PostgreSQL. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
@Tag("integration") @Tag("EO03") @Tag("EC-NEW3") @Tag("EC07") @Tag("TS01") @Tag("TS02") @Tag("AT01") @Tag("AT02")
class ReleaseOneIntegrationRepairTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    FlowSupport flow;

    @BeforeEach void setUp() { flow = new FlowSupport(mvc, users); }
    void sync() { em.flush(); em.clear(); }
    String details(int attendance, int capacity) {
        return FlowSupport.requestBody("Repair workshop", attendance, "none")
                .replace("\"registrationNeeds\":false", "\"registrationNeeds\":true,\"registrationCapacity\":" + capacity
                        + ",\"registrationOpensAt\":\"2026-01-01T09:00:00+08:00\",\"registrationClosesAt\":\"2027-03-09T09:00:00+08:00\"");
    }
    UUID requestId(UUID event) {
        sync();
        return jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id=?", UUID.class, event);
    }
    String proposal(UUID request, String details, boolean confirmed) {
        return "{\"requestId\":\"" + request + "\",\"reason\":\"Release 1 change\",\"capacityConfirmed\":" + confirmed + ",\"details\":" + details + "}";
    }
    String ask(UUID request, String details, boolean confirmed) throws Exception {
        String result = mvc.perform(post("/api/change-requests").with(flow.as("eo1"))
                .contentType(MediaType.APPLICATION_JSON).content(proposal(request, details, confirmed)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync(); return flow.read(result).get("changeRequestId").asString();
    }
    void approve(String change) throws Exception {
        List<String> keys = new ArrayList<>();
        flow.read(mvc.perform(get("/api/change-requests/" + change).with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("impact").forEach(i -> {
                    if ("attention".equals(i.get("status").asString())) keys.add("\"" + i.get("key").asString() + "\"");
                });
        mvc.perform(post("/api/change-requests/" + change + "/approve").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[" + String.join(",", keys) + "]}"))
                .andExpect(status().isOk());
        sync();
    }

    @Test @DisplayName("AT01 (negative): a past opening does not allow initial unconfirmed registration")
    void initialPlanningCannotRegister() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", details(200, 200));
        mvc.perform(post("/api/attendee/events/" + event + "/registrations").with(flow.as("att1")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id=?", Integer.class, event)).isZero();
        mvc.perform(get("/api/events/" + event + "/registrations").with(flow.as("eo1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationState").value("not_yet_open"));
    }

    @Test @DisplayName("EO03 EC-NEW3 AT02 (happy): approved attendance 200 to 150 reaches ATT while independent capacity is explicitly confirmed")
    void attendanceAndCapacityRemainDistinct() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", details(200, 200));
        UUID request = requestId(event);
        mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                .content(proposal(request, details(150, 200), false)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.registrationCapacity").exists());
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM events WHERE event_id=?", Integer.class, event)).isEqualTo(200);
        String change = ask(request, details(150, 200), true);
        approve(change);
        jdbc.update("UPDATE events SET status='confirmed', first_confirmed_at=now(), confirmed_at=now() WHERE event_id=?", event);
        em.clear();
        mvc.perform(get("/api/attendee/events/" + event).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedAttendance").value(150))
                .andExpect(jsonPath("$.registrationCapacity").value(200)).andExpect(jsonPath("$.availablePlaces").value(200));
        mvc.perform(post("/api/attendee/events/" + event + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk());
        sync();
        mvc.perform(get("/api/attendee/events/" + event).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availablePlaces").value(199))
                .andExpect(jsonPath("$.registrationClosesAt").exists());
    }

    @Test @DisplayName("EO03 EC-NEW3 (conflict): approval rechecks registrations that arrived after the capacity proposal")
    void approvalCannotDiscardNewRegistrations() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", details(200, 200));
        UUID request = requestId(event);
        String change = ask(request, details(150, 1), false);
        for (int i = 0; i < 2; i++) jdbc.update("""
            INSERT INTO event_registrations(registration_id,event_id,attendee_name,reference,registered_at,status)
            VALUES (?,?,'Historical attendee',?,now(),'confirmed')
            """, UUID.randomUUID(), event, "R-" + UUID.randomUUID());
        mvc.perform(post("/api/change-requests/" + change + "/approve").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[]}"))
                .andExpect(status().isUnprocessableEntity());
        sync();
        assertThat(jdbc.queryForObject("SELECT registration_capacity FROM events WHERE event_id=?", Integer.class, event)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id=?", Integer.class, event)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status::text FROM event_change_requests WHERE change_request_id=?", String.class, UUID.fromString(change))).isEqualTo("pending");
    }

    @Test @DisplayName("EO03 EC-NEW3 AT02 (happy): explicitly reducing attendance and capacity to 150 preserves registrations and leaves 148 places")
    void approvedCapacityReductionPreservesRegistrations() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", details(200, 200));
        UUID request = requestId(event);
        jdbc.update("UPDATE events SET status='confirmed', first_confirmed_at=now(), confirmed_at=now() WHERE event_id=?", event);
        em.clear();
        for (String attendee : List.of("att1", "att2")) {
            mvc.perform(post("/api/attendee/events/" + event + "/registrations").with(flow.as(attendee)))
                    .andExpect(status().isOk());
        }
        sync();
        approve(ask(request, details(150, 150), false));
        mvc.perform(get("/api/attendee/events/" + event).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedAttendance").value(150))
                .andExpect(jsonPath("$.registrationCapacity").value(150)).andExpect(jsonPath("$.availablePlaces").value(148))
                .andExpect(jsonPath("$.status").value("reconfirming")).andExpect(jsonPath("$.registrationReference").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id=? AND status='confirmed'", Integer.class, event)).isEqualTo(2);
    }

    @Test @DisplayName("EO03 EC-NEW3 EC07 TS01 TS02 (happy): structured change flags reassessment and EC revision reaches TS with actual quantities")
    void structuredEquipmentRevision() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Equipment repair", 150, "none"));
        UUID request = requestId(event), equipment = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments(equipment_id,equipment_name,equipment_qty,serialised,equipment_type) VALUES (?,'Repair projector',10,false,'A')", equipment);
        String oldBody = "{\"technicalRequirement\":\"Projection\",\"items\":[{\"equipmentId\":\"" + equipment + "\",\"quantity\":2}]}";
        String old = flow.read(mvc.perform(post("/api/events/" + event + "/equipment-request").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content(oldBody)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("requestId").asString();
        sync();
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + event + "\",\"equipmentId\":\"" + equipment
                        + "\",\"quantity\":2,\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                .andExpect(status().isCreated());
        sync();
        mvc.perform(patch("/api/equipment-requests/" + old + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}")).andExpect(status().isOk());
        String changed = FlowSupport.requestBody("Equipment repair", 150, "none").replace("\"registrationNeeds\":false",
                "\"registrationNeeds\":false,\"equipmentItems\":[{\"equipmentId\":\"" + equipment + "\",\"quantity\":3}]");
        String change = ask(request, changed, false);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id=?", String.class, UUID.fromString(old))).isEqualTo("approved");
        approve(change);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id=?", String.class, UUID.fromString(old))).isEqualTo("attention");
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM equipment_logs WHERE event_id=?", Integer.class, event)).isEqualTo(2);
        mvc.perform(patch("/api/events/" + event + "/equipment-request/" + old + "/still-suitable").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Try keeping the old quantities\"}"))
                .andExpect(status().isConflict());
        String revised = flow.read(mvc.perform(patch("/api/events/" + event + "/equipment-request").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content(oldBody.replace("\"quantity\":2", "\"quantity\":3")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("requestId").asString();
        sync();
        mvc.perform(get("/api/equipment-requests/" + revised + "/lines").with(flow.as("ts1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].equipmentName").value("Repair projector"))
                .andExpect(jsonPath("$[0].quantity").value(3));
        // D40: the approved request keeps its 2 reserved until Technical Support approve the revision.
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM equipment_logs WHERE event_id=?", Integer.class, event)).isEqualTo(2);
        mvc.perform(patch("/api/equipment-requests/" + revised + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + event + "\",\"equipmentId\":\"" + equipment
                        + "\",\"quantity\":1,\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                .andExpect(status().isCreated());
        sync();
        mvc.perform(patch("/api/equipment-requests/" + revised + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));
        sync();
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM equipment_logs WHERE event_id=?", Integer.class, event)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id=?", String.class, UUID.fromString(old))).isEqualTo("superseded");
    }

    @Test @DisplayName("EO03 (negative): invalid catalogue IDs, duplicate types and nonpositive quantities save no proposal")
    void invalidStructuredSelectionsCannotBeSubmitted() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Invalid equipment", 150, "none"));
        UUID request = requestId(event), equipment = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments(equipment_id,equipment_name,equipment_qty,serialised,equipment_type) VALUES (?,'Validation projector',10,false,'A')", equipment);
        String item = "{\"equipmentId\":\"" + equipment + "\",\"quantity\":3}";
        for (String invalid : List.of(item.replace("3}", "0}"), item + "," + item,
                item.replace(equipment.toString(), UUID.randomUUID().toString()), "null")) {
            String details = FlowSupport.requestBody("Invalid equipment", 150, "none").replace("\"registrationNeeds\":false",
                    "\"registrationNeeds\":false,\"equipmentItems\":[" + invalid + "]");
            mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                    .content(proposal(request, details, false))).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.fieldErrors.equipmentItems").exists());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_change_requests WHERE request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("SELECT equipment_items::text FROM events WHERE event_id=?", String.class, event)).isEqualTo("[]");
    }

    @Test @DisplayName("EO03 EC-NEW3 (cross-cutting): legacy backfill keeps approved registration settings authoritative without changing registrations")
    void legacyRegistrationSettingsBackfill() throws Exception {
        UUID event = flow.approvedEvent("eo1", "ec1", details(200, 200));
        UUID request = requestId(event);
        jdbc.update("UPDATE event_requests SET registration_capacity=111, registration_opens_at=null, registration_closes_at=null WHERE request_id=?", request);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V71__align_approved_registration_settings.sql"))
                .execute(jdbc.getDataSource());
        em.clear();
        mvc.perform(get("/api/event-requests/" + request).with(flow.as("eo1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationCapacity").value(200))
                .andExpect(jsonPath("$.registrationOpensAt").isNotEmpty()).andExpect(jsonPath("$.registrationClosesAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT registration_capacity FROM events WHERE event_id=?", Integer.class, event)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id=?", Integer.class, event)).isZero();
    }
}
