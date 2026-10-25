package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * TS09's Technical Support console on real PostgreSQL (decision-log D32):
 * the request list by status, every unit's current and upcoming status
 * periods, the catalogue's availability for a window, one kind's detail and
 * reservations, TS03's refusal the console routes to TS09, and a damage
 * report that blocks a unit as Unavailable (V39).
 *
 * <p>Set-up goes through the real API (EC07 request, TS02 reservation, TS03
 * status period, TS06 decision), so the rules of TS01 (availability), TS02
 * (reservations) and TS03 (status periods) are exercised too. The ACs these
 * extensions serve were not given as numbered criteria, so names carry the
 * story and the case only. Other tests' rows may share the database, so
 * every list is narrowed to this test's own kinds or events.
 */
@Tag("integration")
@Tag("TS09")
@Tag("TS01")
@Tag("TS02")
@Tag("TS03")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TechnicalSupportConsoleFlowTest {

    private static final String MARCH_10 = "2027-03-10";
    private static final String MARCH_11 = "2027-03-11";
    private static final String MARCH_12 = "2027-03-12";
    private static final String MARCH_13 = "2027-03-13";
    private static final String MARCH_14 = "2027-03-14";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    // ---- Set-up through the real API ----------------------------------------

    /** Each event runs 09:00–12:00 Singapore time (01:00–04:00Z) on its day. */
    private static String start(String day) {
        return day + "T01:00:00Z";
    }

    private static String end(String day) {
        return day + "T04:00:00Z";
    }

    private UUID event(String name, String day) throws Exception {
        String body = """
                {"eventName":"%s","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"%sT09:00:00+08:00","endDatetime":"%sT12:00:00+08:00",
                 "expectedAttendance":100,"venueRequirements":"Theatre seating",
                 "accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(name, day, day);
        return flow.approvedEvent("eo1", "ec1", body);
    }

    private UUID equipment(String name, int quantity, boolean serialised) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, ?, ?, ?, 'E')
                """, id, name, quantity, serialised);
        return id;
    }

    private UUID projectors(String... serials) {
        UUID id = equipment("Console Projector " + UUID.randomUUID(), serials.length, true);
        for (String serial : serials) {
            jdbc.update("INSERT INTO serialised_equipments (equipment_id, serial_number, status) "
                    + "VALUES (?, ?, 'AVAILABLE'::equipment_status)", id, serial);
        }
        return id;
    }

    private UUID request(UUID eventId, UUID equipmentId, int quantity, UUID shortfall) throws Exception {
        String body = "{\"technicalRequirement\":\"Stage\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":%d}]%s}"
                .formatted(equipmentId, quantity,
                        shortfall == null ? "" : ",\"shortfallOutcomeId\":\"" + shortfall + "\"");
        String created = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(created).get("requestId").asString());
    }

    private void reserve(UUID eventId, UUID equipmentId, int quantity, String serial, String day) throws Exception {
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,\"serialNumber\":%s,\"start\":\"%s\",\"end\":\"%s\"}"
                                .formatted(eventId, equipmentId, quantity,
                                        serial == null ? "null" : "\"" + serial + "\"", start(day), end(day))))
                .andExpect(status().isCreated());
        sync();
    }

    private void decideRequest(UUID requestId, String body) throws Exception {
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        sync();
    }

    private ResultActions block(UUID equipmentId, String serial, String status, String from, String until)
            throws Exception {
        return mvc.perform(post("/api/equipment/" + equipmentId + "/units/" + serial + "/periods").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\",\"start\":\"%s\",\"end\":%s}"
                        .formatted(status, from, until == null ? "null" : "\"" + until + "\"")));
    }

    private ResultActions report(String body) throws Exception {
        return mvc.perform(post("/api/equipment-damage-reports").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String unitReport(UUID equipmentId, String serial, String from, String until, String extra) {
        return "{\"equipmentId\":\"%s\",\"serialNumber\":\"%s\",\"description\":\"Lamp failed\",\"periodStart\":\"%s\",\"periodEnd\":%s%s}"
                .formatted(equipmentId, serial, from, until == null ? "null" : "\"" + until + "\"", extra);
    }

    private JsonNode getJson(String path, String user, String... params) throws Exception {
        var request = get(path).with(flow.as(user));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(body);
    }

    /** The elements of a JSON array whose {@code field} is one of {@code ids}, in response order. */
    private static List<JsonNode> only(JsonNode array, String field, Object... ids) {
        List<String> wanted = new ArrayList<>();
        for (Object id : ids) {
            wanted.add(id.toString());
        }
        List<JsonNode> kept = new ArrayList<>();
        array.forEach(node -> {
            if (wanted.contains(node.get(field).asString())) {
                kept.add(node);
            }
        });
        return kept;
    }

    private static List<String> values(List<JsonNode> nodes, String field) {
        return nodes.stream().map(node -> node.get(field).asString()).toList();
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int periodsFor(UUID equipmentId) {
        sync();
        return jdbc.queryForObject("SELECT count(*) FROM equipment_status_periods WHERE equipment_id = ?",
                Integer.class, equipmentId);
    }

    // ---- Requests by status --------------------------------------------------

    @Test
    @DisplayName("TS09 (happy): the request list defaults to requests awaiting review, soonest event first, with organisation, coordinator, items and journey status")
    void requestListDefaultsToProcessingWithTheNewFields() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 10, false);
        UUID later = event("Console Later", MARCH_11);
        UUID sooner = event("Console Sooner", MARCH_10);
        UUID laterRequest = request(later, mics, 2, null);
        UUID soonerRequest = request(sooner, mics, 1, null);

        List<JsonNode> listed = only(getJson("/api/equipment-requests", "ts1"), "requestId", laterRequest, soonerRequest);

        assertThat(values(listed, "requestId")).containsExactly(soonerRequest.toString(), laterRequest.toString());
        JsonNode first = listed.get(1);
        assertThat(first.get("eventId").asString()).isEqualTo(later.toString());
        assertThat(first.get("eventName").asString()).isEqualTo("Console Later");
        assertThat(Instant.parse(first.get("eventStart").asString())).isEqualTo(Instant.parse(start(MARCH_11)));
        assertThat(Instant.parse(first.get("eventEnd").asString())).isEqualTo(Instant.parse(end(MARCH_11)));
        assertThat(first.get("technicalRequirement").asString()).isEqualTo("Stage");
        assertThat(first.get("status").asString()).isEqualTo("processing");
        assertThat(first.get("organisationName").asString())
                .isEqualTo(users.findByUsername("eo1").orElseThrow().getOrganisation());
        assertThat(first.get("requestedBy").get("userId").asString()).isEqualTo(flow.idOf("ec1").toString());
        assertThat(first.get("requestedBy").get("name").asString()).isEqualTo("ec1");
        assertThat(first.get("submittedAt").isNull()).isTrue();
        assertThat(first.get("isShortfallRequest").asBoolean()).isFalse();
        assertThat(first.get("eventStatus").asString()).isEqualTo("PLANNING");
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("items").get(0).get("equipmentId").asString()).isEqualTo(mics.toString());
        assertThat(first.get("items").get(0).get("equipmentName").asString()).startsWith("Console Mic ");
        assertThat(first.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("TS09 (happy): status=approved, rejected and all each list only those requests; cancelled events' requests never appear")
    void requestListFiltersByStatus() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 10, false);
        UUID processingEvent = event("Console Waiting", MARCH_11);
        UUID approvedEvent = event("Console Approved", MARCH_12);
        UUID rejectedEvent = event("Console Rejected", MARCH_13);
        UUID cancelledEvent = event("Console Cancelled", MARCH_14);
        UUID processing = request(processingEvent, mics, 1, null);
        UUID approved = request(approvedEvent, mics, 1, null);
        reserve(approvedEvent, mics, 1, null, MARCH_12);
        decideRequest(approved, "{\"status\":\"approved\"}");
        UUID rejected = request(rejectedEvent, mics, 1, null);
        decideRequest(rejected, "{\"status\":\"rejected\",\"rejectReason\":\"Not in stock\"}");
        UUID cancelled = request(cancelledEvent, mics, 1, null);
        jdbc.update("UPDATE events SET status = 'cancelled'::event_status WHERE event_id = ?", cancelledEvent);
        sync();
        Object[] mine = {processing, approved, rejected, cancelled};

        assertThat(values(only(getJson("/api/equipment-requests", "ts1", "status", "approved"), "requestId", mine),
                "requestId")).containsExactly(approved.toString());
        assertThat(values(only(getJson("/api/equipment-requests", "ts1", "status", "rejected"), "requestId", mine),
                "requestId")).containsExactly(rejected.toString());
        assertThat(values(only(getJson("/api/equipment-requests", "ts1", "status", "processing"), "requestId", mine),
                "requestId")).containsExactly(processing.toString());
        assertThat(values(only(getJson("/api/equipment-requests", "ts1", "status", "all"), "requestId", mine),
                "requestId")).containsExactly(processing.toString(), approved.toString(), rejected.toString());
    }

    @Test
    @DisplayName("TS09 (negative): an unknown status is refused with 400")
    void unknownRequestStatusIsRefused() throws Exception {
        mvc.perform(get("/api/equipment-requests").param("status", "pending").with(flow.as("ts1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be processing, approved, rejected or all."));
    }

    @Test
    @DisplayName("TS09 (cross-cutting): /processing still answers, with the same list as ?status=processing and its original fields unchanged")
    void processingPathIsUnchanged() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 10, false);
        UUID eventId = event("Console Original", MARCH_10);
        UUID requestId = request(eventId, mics, 1, null);

        JsonNode original = getJson("/api/equipment-requests/processing", "ts1");
        assertThat(original).isEqualTo(getJson("/api/equipment-requests", "ts1", "status", "processing"));

        JsonNode mine = only(original, "requestId", requestId).getFirst();
        assertThat(mine.get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(mine.get("eventName").asString()).isEqualTo("Console Original");
        assertThat(Instant.parse(mine.get("eventStart").asString())).isEqualTo(Instant.parse(start(MARCH_10)));
        assertThat(Instant.parse(mine.get("eventEnd").asString())).isEqualTo(Instant.parse(end(MARCH_10)));
        assertThat(mine.get("technicalRequirement").asString()).isEqualTo("Stage");
        assertThat(mine.get("status").asString()).isEqualTo("processing");
    }

    @Test
    @DisplayName("TS09 (boundary): an event with no coordinator gives requestedBy null rather than another user")
    void requestWithoutCoordinatorHasNoRequester() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 10, false);
        UUID eventId = event("Console Unassigned", MARCH_10);
        UUID requestId = request(eventId, mics, 1, null);
        jdbc.update("UPDATE events SET coordinator_id = NULL WHERE event_id = ?", eventId);
        sync();

        JsonNode mine = only(getJson("/api/equipment-requests", "ts1"), "requestId", requestId).getFirst();

        assertThat(mine.get("requestedBy").isNull()).isTrue();
    }

    @Test
    @DisplayName("TS09 (happy): a coordinator's additional request for a shortfall is flagged isShortfallRequest; the original approved request is not")
    void shortfallRequestIsFlagged() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("Console Short", MARCH_10);
        UUID original = request(eventId, projectors, 1, null);
        reserve(eventId, projectors, 1, "PJ-1", MARCH_10);
        decideRequest(original, "{\"status\":\"approved\"}");
        JsonNode report = flow.read(report(unitReport(projectors, "PJ-1", "2027-03-01T00:00:00Z", null, ""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        sync();
        String outcomeId = report.get("affectedEvents").get(0).get("outcomeId").asString();
        mvc.perform(post("/api/equipment-damage-reports/" + report.get("reportId").asString() + "/outcomes/"
                        + outcomeId).with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SHORT\"}"))
                .andExpect(status().isOk());
        sync();
        UUID additional = request(eventId, projectors, 1, UUID.fromString(outcomeId));

        List<JsonNode> listed = only(getJson("/api/equipment-requests", "ts1", "status", "all"),
                "requestId", original, additional);

        assertThat(listed).hasSize(2);
        for (JsonNode node : listed) {
            assertThat(node.get("isShortfallRequest").asBoolean())
                    .isEqualTo(node.get("requestId").asString().equals(additional.toString()));
        }
    }

    // ---- Status periods ------------------------------------------------------

    @Test
    @DisplayName("TS09 (happy): current status periods list running and upcoming blocks, newest start first, and leave out ended ones")
    void currentStatusPeriodsLeaveOutEndedBlocks() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        block(projectors, "PJ-1", "Faulty", "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z").andExpect(status().isNoContent());
        block(projectors, "PJ-2", "Unavailable", "2020-01-01T00:00:00Z", null).andExpect(status().isNoContent());
        block(projectors, "PJ-3", "Faulty", "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z").andExpect(status().isNoContent());
        sync();

        List<JsonNode> current = only(getJson("/api/equipment/status-periods", "ts1", "current", "true"),
                "equipmentId", projectors);

        assertThat(values(current, "serialNumber")).containsExactly("PJ-3", "PJ-2");
        JsonNode upcoming = current.getFirst();
        assertThat(upcoming.get("id").asString()).isNotBlank();
        assertThat(upcoming.get("equipmentName").asString()).startsWith("Console Projector ");
        assertThat(upcoming.get("status").asString()).isEqualTo("Faulty");
        assertThat(Instant.parse(upcoming.get("start").asString())).isEqualTo(Instant.parse("2027-05-01T00:00:00Z"));
        assertThat(Instant.parse(upcoming.get("end").asString())).isEqualTo(Instant.parse("2027-06-01T00:00:00Z"));
        assertThat(current.get(1).get("status").asString()).isEqualTo("Unavailable");
        assertThat(current.get(1).get("end").isNull()).isTrue();

        List<JsonNode> all = only(getJson("/api/equipment/status-periods", "ts1", "current", "false"),
                "equipmentId", projectors);
        assertThat(values(all, "serialNumber")).containsExactly("PJ-3", "PJ-1", "PJ-2");
    }

    @Test
    @DisplayName("TS09 (happy): damage reports can be narrowed to one equipment kind")
    void damageReportsFilterByEquipment() throws Exception {
        UUID first = projectors("PJ-1");
        UUID second = projectors("PJ-1");
        report(unitReport(first, "PJ-1", "2027-04-01T00:00:00Z", null, "")).andExpect(status().isCreated());
        report(unitReport(second, "PJ-1", "2027-04-01T00:00:00Z", null, "")).andExpect(status().isCreated());
        sync();

        JsonNode filtered = getJson("/api/equipment-damage-reports", "ts1", "equipmentId", first.toString());

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("equipmentId").asString()).isEqualTo(first.toString());
        assertThat(only(getJson("/api/equipment-damage-reports", "ts1"), "equipmentId", first, second)).hasSize(2);
    }

    // ---- Catalogue for a window ----------------------------------------------

    @Test
    @DisplayName("TS09 (happy): the catalogue for a window gives reserved, out-of-action and available quantities, the same figure TS01's request availability gives")
    void catalogueWindowMatchesRequestAvailability() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 5, false);
        UUID eventId = event("Console Window", MARCH_10);
        UUID requestId = request(eventId, mics, 2, null);
        reserve(eventId, mics, 2, null, MARCH_10);
        report("{\"equipmentId\":\"%s\",\"quantity\":1,\"description\":\"Water damage\",\"periodStart\":\"%s\",\"periodEnd\":\"%s\"}"
                .formatted(mics, start(MARCH_10), end(MARCH_10))).andExpect(status().isCreated());
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        UUID projectorEvent = event("Console Window Projector", MARCH_10);
        request(projectorEvent, projectors, 1, null);
        reserve(projectorEvent, projectors, 1, "PJ-1", MARCH_10);
        block(projectors, "PJ-3", "Faulty", start(MARCH_10), end(MARCH_10)).andExpect(status().isNoContent());
        sync();

        JsonNode catalogue = getJson("/api/equipment/catalogue", "ts1", "from", start(MARCH_10), "to", end(MARCH_10));

        JsonNode mic = only(catalogue, "equipmentId", mics).getFirst();
        assertThat(mic.get("totalQuantity").asInt()).isEqualTo(5);
        assertThat(mic.get("reservedQuantity").asInt()).isEqualTo(2);
        assertThat(mic.get("outOfActionQuantity").asInt()).isEqualTo(1);
        assertThat(mic.get("availableQuantity").asInt()).isEqualTo(2);
        JsonNode projector = only(catalogue, "equipmentId", projectors).getFirst();
        assertThat(projector.get("reservedQuantity").asInt()).isEqualTo(1);
        assertThat(projector.get("outOfActionQuantity").asInt()).isEqualTo(1);
        assertThat(projector.get("availableQuantity").asInt()).isEqualTo(1);

        JsonNode availability = getJson("/api/equipment/requests/" + requestId + "/availability", "ts1",
                "start", start(MARCH_10), "end", end(MARCH_10));
        assertThat(availability.get(0).get("availableQuantity").asInt()).isEqualTo(mic.get("availableQuantity").asInt());
    }

    @Test
    @DisplayName("TS09 (boundary): a window that starts exactly when the reservation and damage end counts neither")
    void catalogueWindowRightAfterCountsNothing() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 5, false);
        UUID eventId = event("Console Back To Back", MARCH_10);
        request(eventId, mics, 2, null);
        reserve(eventId, mics, 2, null, MARCH_10);
        report("{\"equipmentId\":\"%s\",\"quantity\":1,\"description\":\"Water damage\",\"periodStart\":\"%s\",\"periodEnd\":\"%s\"}"
                .formatted(mics, start(MARCH_10), end(MARCH_10))).andExpect(status().isCreated());
        sync();

        JsonNode mic = only(getJson("/api/equipment/catalogue", "ts1", "from", end(MARCH_10),
                "to", "2027-03-10T05:00:00Z"), "equipmentId", mics).getFirst();

        assertThat(mic.get("reservedQuantity").asInt()).isZero();
        assertThat(mic.get("outOfActionQuantity").asInt()).isZero();
        assertThat(mic.get("availableQuantity").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("TS09 (negative): half a window, or one whose end is not after its start, is refused with 400; one second long is accepted")
    void catalogueWindowMustMoveForward() throws Exception {
        mvc.perform(get("/api/equipment/catalogue").param("from", start(MARCH_10)).with(flow.as("ts1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give both from and to, or neither."));
        mvc.perform(get("/api/equipment/catalogue").param("to", end(MARCH_10)).with(flow.as("ts1")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/equipment/catalogue").param("from", start(MARCH_10)).param("to", start(MARCH_10))
                        .with(flow.as("ts1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be after from."));
        mvc.perform(get("/api/equipment/catalogue").param("from", start(MARCH_10))
                        .param("to", "2027-03-10T01:00:01Z").with(flow.as("ts1")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TS09 (cross-cutting): without a window the catalogue keeps its original four fields for organisers, coordinators and Technical Support")
    void plainCatalogueIsUnchanged() throws Exception {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 5, false);

        for (String user : List.of("eo1", "ec1", "ts1")) {
            JsonNode mic = only(getJson("/api/equipment/catalogue", user), "equipmentId", mics).getFirst();
            List<String> fields = new ArrayList<>(mic.propertyNames());
            assertThat(fields).containsExactlyInAnyOrder("equipmentId", "equipmentName", "totalQuantity", "serialised");
            assertThat(mic.get("totalQuantity").asInt()).isEqualTo(5);
            assertThat(mic.get("serialised").asBoolean()).isFalse();
        }
    }

    // ---- One kind: detail and reservations -----------------------------------

    @Test
    @DisplayName("TS09 (happy): a kind's detail lists each unit with its status now and all its status periods")
    void equipmentDetailListsUnitsAndPeriods() throws Exception {
        UUID projectors = projectors("PJ-2", "PJ-1", "PJ-3");
        block(projectors, "PJ-3", "Faulty", "2020-01-01T00:00:00Z", null).andExpect(status().isNoContent());
        block(projectors, "PJ-2", "Unavailable", "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z")
                .andExpect(status().isNoContent());
        sync();

        JsonNode detail = getJson("/api/equipment/" + projectors, "ts1");

        assertThat(detail.get("equipmentId").asString()).isEqualTo(projectors.toString());
        assertThat(detail.get("equipmentName").asString()).startsWith("Console Projector ");
        assertThat(detail.get("serialised").asBoolean()).isTrue();
        assertThat(detail.get("totalQuantity").asInt()).isEqualTo(3);
        JsonNode units = detail.get("units");
        assertThat(values(List.of(units.get(0), units.get(1), units.get(2)), "serialNumber"))
                .containsExactly("PJ-1", "PJ-2", "PJ-3");
        assertThat(units.get(0).get("status").asString()).isEqualTo("Available");
        assertThat(units.get(0).get("statusPeriods")).isEmpty();
        assertThat(units.get(1).get("status").asString()).isEqualTo("Available");
        assertThat(units.get(1).get("statusPeriods")).hasSize(1);
        assertThat(units.get(1).get("statusPeriods").get(0).get("status").asString()).isEqualTo("Unavailable");
        assertThat(Instant.parse(units.get(1).get("statusPeriods").get(0).get("start").asString()))
                .isEqualTo(Instant.parse("2027-05-01T00:00:00Z"));
        assertThat(units.get(2).get("status").asString()).isEqualTo("Faulty");
        assertThat(units.get(2).get("statusPeriods").get(0).get("end").isNull()).isTrue();
    }

    @Test
    @DisplayName("TS09 (negative): an unknown kind is 404; a kind not tracked by serial number has no units")
    void equipmentDetailEdges() throws Exception {
        mvc.perform(get("/api/equipment/" + UUID.randomUUID()).with(flow.as("ts1")))
                .andExpect(status().isNotFound());
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 4, false);

        JsonNode detail = getJson("/api/equipment/" + mics, "ts1");

        assertThat(detail.get("serialised").asBoolean()).isFalse();
        assertThat(detail.get("totalQuantity").asInt()).isEqualTo(4);
        assertThat(detail.get("units")).isEmpty();
    }

    @Test
    @DisplayName("TS09 (happy): a kind's reservations over a window name each event, earliest first, in the same shape as an event's reservations")
    void equipmentReservationsShareTheEventShape() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID second = event("Console Second", MARCH_11);
        UUID first = event("Console First", MARCH_10);
        request(second, projectors, 1, null);
        request(first, projectors, 1, null);
        reserve(second, projectors, 1, "PJ-2", MARCH_11);
        reserve(first, projectors, 1, "PJ-1", MARCH_10);

        JsonNode listed = getJson("/api/equipment/" + projectors + "/reservations", "ts1",
                "from", "2027-03-10T00:00:00Z", "to", "2027-03-12T00:00:00Z");

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("eventId").asString()).isEqualTo(first.toString());
        assertThat(listed.get(0).get("eventName").asString()).isEqualTo("Console First");
        assertThat(listed.get(0).get("serialNumber").asString()).isEqualTo("PJ-1");
        assertThat(listed.get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(Instant.parse(listed.get(0).get("loanedFrom").asString())).isEqualTo(Instant.parse(start(MARCH_10)));
        assertThat(Instant.parse(listed.get(0).get("loanedUntil").asString())).isEqualTo(Instant.parse(end(MARCH_10)));
        assertThat(listed.get(1).get("eventName").asString()).isEqualTo("Console Second");

        JsonNode byEvent = getJson("/api/equipment/events/" + first + "/reservations", "ts1");
        assertThat(byEvent).hasSize(1);
        assertThat(byEvent.get(0)).isEqualTo(listed.get(0));
    }

    @Test
    @DisplayName("TS09 (boundary): a window that ends exactly when a reservation starts does not include it")
    void equipmentReservationsWindowIsHalfOpen() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("Console Edge", MARCH_10);
        request(eventId, projectors, 1, null);
        reserve(eventId, projectors, 1, "PJ-1", MARCH_10);

        assertThat(getJson("/api/equipment/" + projectors + "/reservations", "ts1",
                "from", "2027-03-10T00:00:00Z", "to", start(MARCH_10))).isEmpty();
        assertThat(getJson("/api/equipment/" + projectors + "/reservations", "ts1",
                "from", "2027-03-10T00:00:00Z", "to", "2027-03-10T01:00:01Z")).hasSize(1);
    }

    @Test
    @DisplayName("TS09 (negative): a kind's reservations need from and to, with to after from; an unknown kind is 404")
    void equipmentReservationsValidation() throws Exception {
        UUID projectors = projectors("PJ-1");
        mvc.perform(get("/api/equipment/" + projectors + "/reservations").param("from", start(MARCH_10))
                        .with(flow.as("ts1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from and to are required, and to must be after from"));
        mvc.perform(get("/api/equipment/" + projectors + "/reservations").param("from", start(MARCH_10))
                        .param("to", start(MARCH_10)).with(flow.as("ts1")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/equipment/" + UUID.randomUUID() + "/reservations").param("from", start(MARCH_10))
                        .param("to", end(MARCH_10)).with(flow.as("ts1")))
                .andExpect(status().isNotFound());
    }

    // ---- TS03's refusal, which the console routes to TS09 ---------------------

    @Test
    @DisplayName("TS09 (conflict): blocking a unit an event holds is refused with 409 and TS03's fixed message, and no block is saved")
    void blockingAReservedUnitIsRefusedWithAStableMessage() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("Console Reserved", MARCH_10);
        request(eventId, projectors, 1, null);
        reserve(eventId, projectors, 1, "PJ-1", MARCH_10);

        block(projectors, "PJ-1", "Faulty", start(MARCH_10), end(MARCH_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This unit is already committed to an event during that time."));

        assertThat(periodsFor(projectors)).isZero();
    }

    // ---- TS09 Unavailable (V39) ----------------------------------------------

    @Test
    @DisplayName("TS09 (happy): a report asking for Unavailable on a unit no event holds closes at once and blocks it Unavailable for the period")
    void unavailableBlockIsAppliedOnClose() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, "PJ-1", "2027-04-01T00:00:00Z", "2027-05-01T00:00:00Z",
                        ",\"blockStatus\":\"UNAVAILABLE\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.blockStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.blockApplied").value(true))
                .andExpect(jsonPath("$.markFaulty").value(false))
                .andExpect(jsonPath("$.faultyMarked").value(false));

        sync();
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_status_periods WHERE equipment_id = ?",
                String.class, projectors)).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT period_end FROM equipment_status_periods WHERE equipment_id = ?",
                Instant.class, projectors)).isEqualTo(Instant.parse("2027-05-01T00:00:00Z"));
    }

    @Test
    @DisplayName("TS09 (happy): a reserved unit asked to be Unavailable is blocked only once its event is replaced and the report closes")
    void unavailableBlockWaitsForTheEventToLetGo() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("Console Replace", MARCH_10);
        UUID requestId = request(eventId, projectors, 1, null);
        reserve(eventId, projectors, 1, "PJ-1", MARCH_10);
        decideRequest(requestId, "{\"status\":\"approved\"}");

        JsonNode report = flow.read(report(unitReport(projectors, "PJ-1", "2027-03-01T00:00:00Z", null,
                        ",\"blockStatus\":\"unavailable\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.blockApplied").value(false))
                .andReturn().getResponse().getContentAsString());
        assertThat(periodsFor(projectors)).isZero();
        String reportId = report.get("reportId").asString();
        mvc.perform(post("/api/equipment-damage-reports/" + reportId + "/outcomes/"
                        + report.get("affectedEvents").get(0).get("outcomeId").asString()).with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/equipment-damage-reports/" + reportId + "/close").with(flow.as("ts1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.blockApplied").value(true));

        sync();
        assertThat(jdbc.queryForList("SELECT status::text FROM equipment_status_periods WHERE equipment_id = ? "
                + "AND serial_number = 'PJ-1'", String.class, projectors)).containsExactly("UNAVAILABLE");
    }

    @Test
    @DisplayName("TS09 (cross-cutting): markFaulty true is still accepted as a Faulty block, and the response keeps markFaulty and faultyMarked")
    void markFaultyStillMeansFaulty() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, "PJ-1", "2027-04-01T00:00:00Z", null, ",\"markFaulty\":true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockStatus").value("FAULTY"))
                .andExpect(jsonPath("$.markFaulty").value(true))
                .andExpect(jsonPath("$.faultyMarked").value(true))
                .andExpect(jsonPath("$.blockApplied").value(true));

        sync();
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_status_periods WHERE equipment_id = ?",
                String.class, projectors)).isEqualTo("FAULTY");
    }

    @Test
    @DisplayName("TS09 (negative): an unknown block status, markFaulty with a different block status, or a block on a non-serialised kind is refused and nothing is saved")
    void invalidBlockRequestsAreRefused() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 5, false);

        report(unitReport(projectors, "PJ-1", "2027-04-01T00:00:00Z", null, ",\"blockStatus\":\"AVAILABLE\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("blockStatus must be FAULTY, UNAVAILABLE or left out."));
        report(unitReport(projectors, "PJ-1", "2027-04-01T00:00:00Z", null,
                        ",\"markFaulty\":true,\"blockStatus\":\"UNAVAILABLE\""))
                .andExpect(status().isBadRequest());
        report("{\"equipmentId\":\"%s\",\"quantity\":1,\"description\":\"Frayed\",\"periodStart\":\"2027-04-01T00:00:00Z\",\"blockStatus\":\"UNAVAILABLE\"}"
                .formatted(mics))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a serialised unit can be marked Faulty or Unavailable."));

        sync();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_damage_reports WHERE equipment_id IN (?, ?)",
                Integer.class, projectors, mics)).isZero();
        assertThat(periodsFor(projectors)).isZero();
    }

    @Test
    @DisplayName("TS09 (failure): the database itself refuses a block on a non-serialised report (V39)")
    void schemaRefusesABlockWithoutAUnit() {
        UUID mics = equipment("Console Mic " + UUID.randomUUID(), 5, false);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO equipment_damage_reports (report_id, equipment_id, quantity, description, period_start,
                    block_status, status, reported_by, reported_at)
                VALUES (?, ?, 1, 'Frayed', now(), 'UNAVAILABLE', 'OPEN', ?, now())
                """, UUID.randomUUID(), mics, flow.idOf("ts1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
