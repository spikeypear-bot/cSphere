package com.example.connect_sphere.equipmentdamage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * TS09 end to end on real PostgreSQL: Technical Support report damaged
 * equipment, decide each affected event, and the coordinator deals with a
 * shortfall. Set-up goes through the real API (EC07 request, TS02
 * reservation, TS06 approval), so the stories TS09 builds on are exercised
 * too. AC numbers are TS09's 22 acceptance criteria.
 */
@Tag("integration")
@Tag("TS09")
@Tag("TS01")
@Tag("TS02")
@Tag("TS06")
@Tag("TS03")
@Tag("EC07")
@Tag("EO04")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentDamageFlowTest {

    /** Each event runs 09:00–12:00 Singapore time (01:00–04:00Z) on its day. */
    private static final String MARCH_10 = "2027-03-10";
    private static final String MARCH_11 = "2027-03-11";
    private static final String DAMAGED_FROM = "2027-03-01T00:00:00Z";

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
        UUID id = equipment("TS09 Projector " + UUID.randomUUID(), serials.length, true);
        for (String serial : serials) {
            jdbc.update("INSERT INTO serialised_equipments (equipment_id, serial_number, status) "
                    + "VALUES (?, ?, 'AVAILABLE'::equipment_status)", id, serial);
        }
        return id;
    }

    private UUID request(UUID eventId, UUID equipmentId, int quantity, UUID shortfall) throws Exception {
        String body = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(equipmentId, quantity, shortfall)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(body).get("requestId").asString());
    }

    private static String requestBody(UUID equipmentId, int quantity, UUID shortfall) {
        return "{\"technicalRequirement\":\"Stage\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":%d}]%s}"
                .formatted(equipmentId, quantity,
                        shortfall == null ? "" : ",\"shortfallOutcomeId\":\"" + shortfall + "\"");
    }

    private void reserve(UUID eventId, UUID equipmentId, int quantity, String serial, String day) throws Exception {
        reserve(eventId, equipmentId, quantity, serial, day, false);
    }

    private void reserve(UUID eventId, UUID equipmentId, int quantity, String serial, String day, boolean spare)
            throws Exception {
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,\"serialNumber\":%s,\"start\":\"%s\",\"end\":\"%s\",\"spare\":%s}"
                                .formatted(eventId, equipmentId, quantity,
                                        serial == null ? "null" : "\"" + serial + "\"", start(day), end(day), spare)))
                .andExpect(status().isCreated());
        sync();
    }

    private ResultActions decideRequest(UUID requestId, String status) throws Exception {
        return mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    /** EC07 → TS02 → TS06: an approved request of {@code requested} units, with these serials reserved. */
    private UUID approvedUnits(UUID eventId, UUID equipmentId, int requested, String day, String... serials)
            throws Exception {
        UUID requestId = request(eventId, equipmentId, requested, null);
        // D33: units beyond the requested quantity are reserved as spares.
        for (int i = 0; i < serials.length; i++) {
            reserve(eventId, equipmentId, 1, serials[i], day, i >= requested);
        }
        decideRequest(requestId, "approved").andExpect(status().isOk());
        sync();
        return requestId;
    }

    /** As above for a non-serialised kind: {@code reserved} units against a request for {@code requested}. */
    private UUID approvedQuantity(UUID eventId, UUID equipmentId, int requested, int reserved, String day)
            throws Exception {
        UUID requestId = request(eventId, equipmentId, requested, null);
        reserve(eventId, equipmentId, reserved, null, day);
        decideRequest(requestId, "approved").andExpect(status().isOk());
        sync();
        return requestId;
    }

    private static String unitReport(UUID equipmentId, String serial, String description, String from, String until,
            String blockStatus) {
        return "{\"equipmentId\":\"%s\",\"serialNumber\":%s,\"description\":%s,\"periodStart\":%s,\"periodEnd\":%s,\"blockStatus\":%s}"
                .formatted(equipmentId, quote(serial), quote(description), quote(from), quote(until), quote(blockStatus));
    }

    private static String quantityReport(UUID equipmentId, Integer quantity, String from, String until) {
        return "{\"equipmentId\":\"%s\",\"quantity\":%s,\"description\":\"Water damage\",\"periodStart\":%s,\"periodEnd\":%s}"
                .formatted(equipmentId, quantity, quote(from), quote(until));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private ResultActions report(String body) throws Exception {
        return mvc.perform(post("/api/equipment-damage-reports").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode reported(String body) throws Exception {
        String response = report(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return flow.read(response);
    }

    private ResultActions decide(JsonNode report, UUID eventId, String body) throws Exception {
        return mvc.perform(post("/api/equipment-damage-reports/" + report.get("reportId").asString()
                        + "/outcomes/" + outcomeOf(report, eventId)).with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions close(JsonNode report) throws Exception {
        return mvc.perform(post("/api/equipment-damage-reports/" + report.get("reportId").asString() + "/close")
                .with(flow.as("ts1")));
    }

    private static UUID outcomeOf(JsonNode report, UUID eventId) {
        for (JsonNode affected : report.get("affectedEvents")) {
            if (affected.get("eventId").asString().equals(eventId.toString())) {
                return UUID.fromString(affected.get("outcomeId").asString());
            }
        }
        throw new AssertionError("Event " + eventId + " is not affected");
    }

    private static List<String> affectedIds(JsonNode report) {
        List<String> ids = new java.util.ArrayList<>();
        report.get("affectedEvents").forEach(a -> ids.add(a.get("eventId").asString()));
        return ids;
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> serialsReservedFor(UUID eventId, UUID equipmentId) {
        sync();
        return jdbc.queryForList("SELECT serial_number FROM equipment_logs WHERE event_id = ? AND equipment_id = ? "
                + "ORDER BY serial_number", String.class, eventId, equipmentId);
    }

    private int unitsReservedFor(UUID eventId, UUID equipmentId) {
        sync();
        return jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM equipment_logs WHERE event_id = ? "
                + "AND equipment_id = ?", Integer.class, eventId, equipmentId);
    }

    private String eventStatus(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    private int reportsFor(UUID equipmentId) {
        sync();
        return jdbc.queryForObject("SELECT count(*) FROM equipment_damage_reports WHERE equipment_id = ?",
                Integer.class, equipmentId);
    }

    private String outcomeColumn(UUID outcomeId, String column) {
        sync();
        return jdbc.queryForObject("SELECT " + column + "::text FROM equipment_damage_outcomes WHERE outcome_id = ?",
                String.class, outcomeId);
    }

    private void setStatus(UUID eventId, String status) {
        sync();
        jdbc.update("UPDATE events SET status = ?::event_status, first_confirmed_at = now(), confirmed_at = now() "
                + "WHERE event_id = ?", status, eventId);
    }

    // ---- Reporting (AC1–AC8) ------------------------------------------------

    @Test
    @DisplayName("TS09 AC1, AC2, AC6 (happy): reporting a reserved unit saves an open report listing the event that relies on it, still undecided")
    void reportingAUnitListsTheEventThatReliesOnIt() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");

        JsonNode report = reported(unitReport(projectors, "PJ-1", "Lamp exploded", DAMAGED_FROM, null, null));

        assertThat(report.get("status").asString()).isEqualTo("OPEN");
        assertThat(report.get("serialNumber").asString()).isEqualTo("PJ-1");
        assertThat(report.get("quantity").asInt()).isEqualTo(1);
        assertThat(report.get("description").asString()).isEqualTo("Lamp exploded");
        assertThat(report.get("reportedByName").asString()).isEqualTo("ts1");
        assertThat(affectedIds(report)).containsExactly(eventId.toString());
        JsonNode affected = report.get("affectedEvents").get(0);
        assertThat(affected.get("outcome").isNull()).isTrue();
        assertThat(affected.get("requestedQuantity").asInt()).isEqualTo(1);
        assertThat(affected.get("reservedQuantity").asInt()).isEqualTo(1);
        assertThat(affected.get("affectedQuantity").asInt()).isEqualTo(1);
        assertThat(affected.get("spareCovers").asBoolean()).isFalse();
        assertThat(report.get("canClose").asBoolean()).isFalse();

        mvc.perform(get("/api/equipment-damage-reports").with(flow.as("ts2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reportId", hasItem(report.get("reportId").asString())));
    }

    @Test
    @DisplayName("TS09 AC1, AC7 (negative): a blank description is refused and nothing is saved")
    void aBlankDescriptionIsRefused() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, "PJ-1", "   ", DAMAGED_FROM, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Describe the damage."));

        assertThat(reportsFor(projectors)).isZero();
    }

    @Test
    @DisplayName("TS09 AC1 (boundary): a description of 2,000 characters is accepted and one of 2,001 is refused")
    void theDescriptionIsAtMost2000Characters() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, "PJ-1", "x".repeat(2001), DAMAGED_FROM, null, null))
                .andExpect(status().isBadRequest());
        assertThat(reportsFor(projectors)).isZero();

        report(unitReport(projectors, "PJ-1", "x".repeat(2000), DAMAGED_FROM, null, null))
                .andExpect(status().isCreated());
        assertThat(reportsFor(projectors)).isEqualTo(1);
    }

    @Test
    @DisplayName("TS09 AC1 (boundary): the start is required, and an end before or equal to the start is refused; one second later is accepted")
    void theEndMustBeAfterTheStart() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, "PJ-1", "Cracked", null, null, null)).andExpect(status().isBadRequest());
        report(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, "2027-02-28T23:59:59Z", null))
                .andExpect(status().isBadRequest());
        report(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, DAMAGED_FROM, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The end must be after the start."));
        assertThat(reportsFor(projectors)).isZero();

        report(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, "2027-03-01T00:00:01Z", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodEnd").value("2027-03-01T00:00:01Z"));
    }

    @Test
    @DisplayName("TS09 AC2 (negative): a serialised kind needs an existing unit's serial number")
    void aSerialisedKindNeedsAKnownUnit() throws Exception {
        UUID projectors = projectors("PJ-1");

        report(unitReport(projectors, null, "Cracked", DAMAGED_FROM, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose the damaged unit's serial number."));
        report(unitReport(projectors, "PJ-404", "Cracked", DAMAGED_FROM, null, null))
                .andExpect(status().isNotFound());
        assertThat(reportsFor(projectors)).isZero();
    }

    @Test
    @DisplayName("TS09 AC3 (boundary): a non-serialised kind takes 1 up to the quantity owned; 0, one more than owned, or a serial number are refused")
    void aQuantityIsBetweenOneAndWhatIsOwned() throws Exception {
        UUID cables = equipment("TS09 Cable " + UUID.randomUUID(), 10, false);

        report(quantityReport(cables, 0, DAMAGED_FROM, null)).andExpect(status().isBadRequest());
        report(quantityReport(cables, 11, DAMAGED_FROM, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only 10")));
        report(quantityReport(cables, null, DAMAGED_FROM, null)).andExpect(status().isBadRequest());
        report("{\"equipmentId\":\"" + cables + "\",\"serialNumber\":\"C-1\",\"quantity\":1,"
                + "\"description\":\"Frayed\",\"periodStart\":\"" + DAMAGED_FROM + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(reportsFor(cables)).isZero();

        report(quantityReport(cables, 10, DAMAGED_FROM, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.serialised").value(false));
    }

    @Test
    @DisplayName("TS09 AC6 (negative): events whose request is still processing, cancelled events and reservations outside the period are not listed; a report affecting no event closes itself")
    void onlyActiveApprovedEventsInThePeriodAreAffected() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID processing = event("TS09 Processing", MARCH_10);
        request(processing, projectors, 1, null);
        reserve(processing, projectors, 1, "PJ-1", MARCH_10);
        UUID cancelled = event("TS09 Cancelled", MARCH_11);
        approvedUnits(cancelled, projectors, 1, MARCH_11, "PJ-1");
        setStatus(cancelled, "cancelled");
        UUID later = event("TS09 Later", "2027-04-10");
        approvedUnits(later, projectors, 1, "2027-04-10", "PJ-1");

        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, "2027-04-01T00:00:00Z", null));

        assertThat(affectedIds(report)).isEmpty();
        assertThat(report.get("status").asString()).isEqualTo("CLOSED");
        assertThat(report.get("closedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("TS09 AC7 (happy): equipment on loan to an event that is running now is reported, and that event is affected")
    void equipmentOnLoanNowIsReported() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("TS09 Running", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE events SET start_datetime = ?::timestamptz, end_datetime = ?::timestamptz WHERE event_id = ?",
                now.minus(1, ChronoUnit.HOURS).toString(), now.plus(2, ChronoUnit.HOURS).toString(), eventId);
        jdbc.update("UPDATE equipment_logs SET loaned_from = ?::timestamptz, loaned_until = ?::timestamptz WHERE event_id = ?",
                now.minus(1, ChronoUnit.HOURS).toString(), now.plus(2, ChronoUnit.HOURS).toString(), eventId);

        JsonNode report = reported(unitReport(projectors, "PJ-1", "Dropped on stage", now.toString(), null, null));

        assertThat(affectedIds(report)).containsExactly(eventId.toString());
    }

    @Test
    @DisplayName("TS09 AC8 (happy): saving a report changes no reservation, request, unit status or event status")
    void reportingChangesNothingElse() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("TS09 Gala", MARCH_10);
        UUID requestId = approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        setStatus(eventId, "confirmed");

        reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, "FAULTY"));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-1");
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id = ?",
                String.class, requestId)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_status_periods WHERE equipment_id = ?",
                Integer.class, projectors)).isZero();
        assertThat(eventStatus(eventId)).isEqualTo("confirmed");
    }

    // ---- Outcomes (AC9–AC15) ----------------------------------------------------

    @Test
    @DisplayName("TS09 AC9 (happy): when the event's spare still covers its request, it is recorded as covered and only the damaged unit's reservation is released")
    void aSpareCoversTheDamagedUnit() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        assertThat(report.get("affectedEvents").get(0).get("spareCovers").asBoolean()).isTrue();

        decide(report, eventId, "{\"decision\":\"COVERED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedEvents[0].outcome").value("COVERED"))
                .andExpect(jsonPath("$.affectedEvents[0].shortfall").value(0));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-2");
    }

    @Test
    @DisplayName("TS09 AC9, AC13 (negative): without a spare, 'covered' is refused with the reason and nothing changes")
    void coveredIsRefusedWithoutASpare() throws Exception {
        UUID projectors = projectors("PJ-1");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"COVERED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("has 0 of the 1")));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-1");
        assertThat(outcomeColumn(outcomeOf(report, eventId), "outcome")).isNull();
    }

    @Test
    @DisplayName("TS09 AC10 (happy): the damaged unit is replaced on the same reservation by a free, working unit")
    void theDamagedUnitIsReplaced() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedEvents[0].outcome").value("REPLACED"))
                .andExpect(jsonPath("$.affectedEvents[0].replacementSerialNumber").value("PJ-2"));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-2");
        assertThat(jdbc.queryForObject("SELECT loaned_from FROM equipment_logs WHERE event_id = ?", Instant.class,
                eventId)).isEqualTo(Instant.parse(start(MARCH_10)));
    }

    @Test
    @DisplayName("TS09 AC10, AC13 (conflict): a replacement that is reserved elsewhere, marked Faulty, the damaged unit itself or unknown is refused, and nothing changes")
    void anUnsuitableReplacementIsRefused() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        UUID other = event("TS09 Other", MARCH_10);
        approvedUnits(other, projectors, 1, MARCH_10, "PJ-2");
        jdbc.update("INSERT INTO equipment_status_periods (equipment_id, serial_number, status, period_start, period_end) "
                + "VALUES (?, 'PJ-3', 'FAULTY'::equipment_status, ?::timestamptz, ?::timestamptz)",
                projectors, "2027-03-10T03:59:00Z", "2027-03-11T00:00:00Z");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("PJ-2 is already reserved for an overlapping event."));
        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-3\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Faulty or Unavailable")));
        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-1\"}")
                .andExpect(status().isBadRequest());
        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-9\"}")
                .andExpect(status().isNotFound());

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-1");
        assertThat(serialsReservedFor(other, projectors)).containsExactly("PJ-2");
        assertThat(outcomeColumn(outcomeOf(report, eventId), "outcome")).isNull();
    }

    @Test
    @DisplayName("TS09 AC10, AC13 (conflict): a replacement with its own open damage report during the event's reservation is refused, and nothing changes")
    void aDamagedReplacementIsRefused() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        UUID other = event("TS09 Other", MARCH_11);
        approvedUnits(other, projectors, 1, MARCH_11, "PJ-2");
        // PJ-2 is free on 10 March, but its report (still open: it affects the other event) covers that day.
        JsonNode damagedSpare = reported(unitReport(projectors, "PJ-2", "Cracked", DAMAGED_FROM, null, null));
        assertThat(damagedSpare.get("status").asString()).isEqualTo("OPEN");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("PJ-2 has been reported damaged during this event's reservation."));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-1");
        assertThat(outcomeColumn(outcomeOf(report, eventId), "outcome")).isNull();
    }

    @Test
    @DisplayName("TS09 AC14 (conflict): the same replacement unit cannot be given to two events at overlapping times; the second attempt is refused")
    void oneReplacementCannotServeTwoOverlappingEvents() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        UUID first = event("TS09 First", MARCH_10);
        approvedUnits(first, projectors, 1, MARCH_10, "PJ-1");
        UUID second = event("TS09 Second", MARCH_10);
        approvedUnits(second, projectors, 1, MARCH_10, "PJ-2");
        JsonNode one = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        JsonNode two = reported(unitReport(projectors, "PJ-2", "Cracked", DAMAGED_FROM, null, null));

        decide(one, first, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-3\"}").andExpect(status().isOk());
        decide(two, second, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-3\"}")
                .andExpect(status().isConflict());

        assertThat(serialsReservedFor(first, projectors)).containsExactly("PJ-3");
        assertThat(serialsReservedFor(second, projectors)).containsExactly("PJ-2");
    }

    @Test
    @DisplayName("TS09 AC12 (happy): with no replacement the event is short by the missing units and the damaged unit's reservation is released")
    void noReplacementLeavesTheEventShort() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"SHORT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedEvents[0].outcome").value("SHORT"))
                .andExpect(jsonPath("$.affectedEvents[0].shortfall").value(1));

        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-2");
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS09 AC12, AC13 (negative): 'short' is refused while a spare still covers the event, and a second decision is refused")
    void shortIsRefusedWhenASpareCovers() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        decide(report, eventId, "{\"decision\":\"SHORT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Record it as covered")));
        assertThat(serialsReservedFor(eventId, projectors)).containsExactly("PJ-1", "PJ-2");

        decide(report, eventId, "{\"decision\":\"COVERED\"}").andExpect(status().isOk());
        decide(report, eventId, "{\"decision\":\"COVERED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An outcome has already been recorded for this event."));
    }

    @Test
    @DisplayName("TS09 AC11, AC12, AC15 (conflict): damaged stock over-commits two events; the report cannot close until reductions fit what is left")
    void nonSerialisedReductionsMustFitTheRemainingStock() throws Exception {
        UUID mics = equipment("TS09 Mic " + UUID.randomUUID(), 10, false);
        UUID big = event("TS09 Big", MARCH_10);
        approvedQuantity(big, mics, 6, 6, MARCH_10);
        UUID small = event("TS09 Small", MARCH_10);
        approvedQuantity(small, mics, 4, 4, MARCH_10);

        JsonNode report = reported(quantityReport(mics, 3, DAMAGED_FROM, null));
        assertThat(affectedIds(report)).containsExactlyInAnyOrder(big.toString(), small.toString());

        decide(report, small, "{\"decision\":\"REDUCE\",\"reduceBy\":0}")
                .andExpect(status().isOk());
        close(report).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("1 affected event still needs an outcome."));
        decide(report, big, "{\"decision\":\"REDUCE\",\"reduceBy\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canClose").value(false));
        close(report).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("exceed the working units by 3")));
        decide(report, big, "{\"decision\":\"REDUCE\",\"reduceBy\":7}")
                .andExpect(status().isBadRequest());

        decide(report, big, "{\"decision\":\"REDUCE\",\"reduceBy\":3}")
                .andExpect(status().isOk());
        assertThat(unitsReservedFor(big, mics)).isEqualTo(3);
        assertThat(unitsReservedFor(small, mics)).isEqualTo(4);
        assertThat(outcomeColumn(outcomeOf(report, big), "outcome")).isEqualTo("SHORT");
        assertThat(outcomeColumn(outcomeOf(report, big), "shortfall")).isEqualTo("3");
        assertThat(outcomeColumn(outcomeOf(report, small), "outcome")).isEqualTo("COVERED");

        close(report).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("TS09 AC5, AC6 (boundary): reservations on different days are not added together, so damage that still leaves each day enough affects no event")
    void reservationsThatNeverOverlapAreNotAddedTogether() throws Exception {
        UUID mics = equipment("TS09 Mic " + UUID.randomUUID(), 5, false);
        UUID first = event("TS09 Day One", MARCH_10);
        UUID requestId = approvedQuantity(first, mics, 4, 4, MARCH_10);
        UUID second = event("TS09 Day Two", MARCH_11);
        approvedQuantity(second, mics, 4, 4, MARCH_11);

        JsonNode report = reported(quantityReport(mics, 1, DAMAGED_FROM, null));

        assertThat(affectedIds(report)).isEmpty();
        mvc.perform(get("/api/equipment/requests/" + requestId + "/availability").with(flow.as("ts1"))
                        .param("start", start(MARCH_10)).param("end", end(MARCH_10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity").value(0));
    }

    @Test
    @DisplayName("TS09 AC15 (negative): a report with an undecided event cannot be closed; once every event has an outcome it closes")
    void aReportClosesOnlyWhenEveryEventHasAnOutcome() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));

        close(report).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("1 affected event still needs an outcome."));

        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}")
                .andExpect(jsonPath("$.canClose").value(true));
        close(report).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        decide(report, eventId, "{\"decision\":\"SHORT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This report is closed."));
        close(report).andExpect(status().isConflict());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FAULTY", "UNAVAILABLE"})
    @DisplayName("TS09 AC4 (happy): a unit an event holds, asked to be blocked, is blocked for the reported period only once its event is replaced and the report closes; a non-serialised kind cannot be blocked")
    void theUnitIsBlockedWhenTheReportCloses(String block) throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 1, MARCH_10, "PJ-1");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, "2027-04-01T00:00:00Z", block));
        assertThat(report.get("status").asString()).isEqualTo("OPEN");
        assertThat(report.get("blockStatus").asString()).isEqualTo(block);
        assertThat(report.get("blockApplied").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_status_periods WHERE equipment_id = ?",
                Integer.class, projectors)).isZero();
        decide(report, eventId, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-2\"}");

        close(report).andExpect(status().isOk())
                .andExpect(jsonPath("$.blockStatus").value(block))
                .andExpect(jsonPath("$.blockApplied").value(true));

        sync();
        assertThat(jdbc.queryForList("SELECT status::text FROM equipment_status_periods WHERE equipment_id = ? "
                + "AND serial_number = 'PJ-1'", String.class, projectors)).containsExactly(block);
        assertThat(jdbc.queryForObject("SELECT period_end FROM equipment_status_periods WHERE equipment_id = ? "
                + "AND serial_number = 'PJ-1'", Instant.class, projectors)).isEqualTo(Instant.parse("2027-04-01T00:00:00Z"));

        UUID cables = equipment("TS09 Cable " + UUID.randomUUID(), 5, false);
        report("{\"equipmentId\":\"" + cables + "\",\"quantity\":1,\"description\":\"Frayed\",\"periodStart\":\""
                + DAMAGED_FROM + "\",\"blockStatus\":\"" + block + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TS09 AC5 (boundary): damaged non-serialised units are not available during the period, and are again once it ends at the event's start")
    void damagedUnitsAreNotAvailableDuringThePeriod() throws Exception {
        UUID mics = equipment("TS09 Mic " + UUID.randomUUID(), 10, false);
        UUID eventId = event("TS09 Gala", MARCH_10);
        UUID requestId = request(eventId, mics, 1, null);

        reported(quantityReport(mics, 3, "2027-03-09T00:00:00Z", start(MARCH_10)));
        mvc.perform(get("/api/equipment/requests/" + requestId + "/availability").with(flow.as("ts1"))
                        .param("start", start(MARCH_10)).param("end", end(MARCH_10)))
                .andExpect(jsonPath("$[0].availableQuantity").value(10));

        reported(quantityReport(mics, 3, "2027-03-10T03:59:59Z", null));
        mvc.perform(get("/api/equipment/requests/" + requestId + "/availability").with(flow.as("ts1"))
                        .param("start", start(MARCH_10)).param("end", end(MARCH_10)))
                .andExpect(jsonPath("$[0].availableQuantity").value(7));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":8,\"serialNumber\":null,\"start\":\"%s\",\"end\":\"%s\"}"
                                .formatted(eventId, mics, start(MARCH_10), end(MARCH_10))))
                .andExpect(status().isConflict());
    }

    // ---- Coordinator (AC16–AC21) ---------------------------------------------------

    @Test
    @DisplayName("TS09 AC17 (cross-cutting): the assigned coordinator sees the outcome and the timeline entry; the Lead reads it without acting; another coordinator and the organiser are refused")
    void theOutcomeIsShownToTheRightPeople() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        decide(report, eventId, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());

        mvc.perform(get("/api/events/" + eventId + "/equipment-damage").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SHORT"))
                .andExpect(jsonPath("$[0].shortfall").value(1))
                .andExpect(jsonPath("$[0].serialNumber").value("PJ-1"))
                .andExpect(jsonPath("$[0].description").value("Cracked"))
                .andExpect(jsonPath("$[0].canResolve").value(true))
                .andExpect(jsonPath("$[0].canRequestAdditional").value(true));
        mvc.perform(get("/api/events/" + eventId + "/equipment-damage").with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SHORT"))
                .andExpect(jsonPath("$[0].canResolve").value(false))
                .andExpect(jsonPath("$[0].canRequestAdditional").value(false));
        mvc.perform(get("/api/events/" + eventId + "/equipment-damage").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/equipment-damage").with(flow.as("eo1")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[*].type", hasItem("equipment_damage_outcome")));
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[*].type", not(hasItem("equipment_damage_outcome"))));
    }

    @Test
    @DisplayName("TS09 AC18 (happy): a confirmed event marked short goes back to Planning; a replaced one keeps its status")
    void aShortConfirmedEventReturnsToPlanning() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        UUID shortEvent = event("TS09 Short", MARCH_10);
        approvedUnits(shortEvent, projectors, 1, MARCH_10, "PJ-1");
        setStatus(shortEvent, "confirmed");
        UUID replaced = event("TS09 Replaced", MARCH_11);
        approvedUnits(replaced, projectors, 1, MARCH_11, "PJ-2");
        setStatus(replaced, "confirmed");
        JsonNode one = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        JsonNode two = reported(unitReport(projectors, "PJ-2", "Cracked", DAMAGED_FROM, null, null));

        decide(one, shortEvent, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());
        decide(two, replaced, "{\"decision\":\"REPLACE\",\"replacementSerialNumber\":\"PJ-3\"}").andExpect(status().isOk());

        assertThat(eventStatus(shortEvent)).isEqualTo("pending");
        assertThat(eventStatus(replaced)).isEqualTo("confirmed");
        mvc.perform(get("/api/events/" + shortEvent + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[*].type", hasItem("returned_to_planning")));
    }

    @Test
    @DisplayName("TS09 AC19, AC21, TS06 (happy): an additional request for the shortfall is accepted beside the approved one, is reviewed as usual, and its approval resolves the shortfall")
    void anAdditionalRequestCoversTheShortfall() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2", "PJ-3");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        decide(report, eventId, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());
        UUID shortfall = outcomeOf(report, eventId);

        UUID additional = request(eventId, projectors, 1, shortfall);
        assertThat(outcomeColumn(shortfall, "additional_request_id")).isEqualTo(additional.toString());

        // TS06 now needs what the event is still owed (2 asked − 1 short = 1) plus this request's 1.
        decideRequest(additional, "approved").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("only 1 of 2")));
        reserve(eventId, projectors, 1, "PJ-3", MARCH_10);
        decideRequest(additional, "approved").andExpect(status().isOk());

        assertThat(outcomeColumn(shortfall, "resolution")).isEqualTo("ADDITIONAL_REQUEST");
        mvc.perform(get("/api/events/" + eventId + "/equipment-damage").with(flow.as("ec1")))
                .andExpect(jsonPath("$[0].additionalRequestStatus").value("approved"))
                .andExpect(jsonPath("$[0].canResolve").value(false))
                .andExpect(jsonPath("$[0].canRequestAdditional").value(false));
    }

    @Test
    @DisplayName("TS09 AC21 (conflict): a second request while one awaits review is refused, and without a shortfall EC07's one-request rule still applies")
    void stillOneRequestAwaitingReviewAtATime() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");

        mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(projectors, 1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This event already has an equipment request awaiting review or already approved."));

        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        decide(report, eventId, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());
        UUID shortfall = outcomeOf(report, eventId);
        request(eventId, projectors, 1, shortfall);

        mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(projectors, 1, shortfall)))
                .andExpect(status().isConflict());
        sync();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_requests WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("TS09 AC20 (negative): the assigned coordinator resolves a shortfall with a note; a blank note, the Lead, another coordinator and a second resolution are refused")
    void aShortfallIsResolvedWithANote() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        decide(report, eventId, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());
        UUID shortfall = outcomeOf(report, eventId);
        String path = "/api/events/" + eventId + "/equipment-damage/" + shortfall + "/resolve";

        mvc.perform(post(path).with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(flow.as("ecl1")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"OK\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(flow.as("ec2")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"OK\"}"))
                .andExpect(status().isForbidden());
        assertThat(outcomeColumn(shortfall, "resolved_at")).isNull();

        mvc.perform(post(path).with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Organiser agreed one projector is enough\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("NOTE"))
                .andExpect(jsonPath("$.resolutionNote").value("Organiser agreed one projector is enough"))
                .andExpect(jsonPath("$.canResolve").value(false));
        mvc.perform(post(path).with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(projectors, 1, shortfall)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/equipment-damage-reports/" + report.get("reportId").asString()).with(flow.as("ts1")))
                .andExpect(jsonPath("$.affectedEvents[0].resolutionNote").value("Organiser agreed one projector is enough"));
    }

    @Test
    @DisplayName("TS09 EO04 (happy): once confirmed, the organiser's arrangements show what the event still has after a shortfall")
    void theOrganiserSeesTheReducedQuantity() throws Exception {
        UUID projectors = projectors("PJ-1", "PJ-2");
        UUID eventId = event("TS09 Gala", MARCH_10);
        approvedUnits(eventId, projectors, 2, MARCH_10, "PJ-1", "PJ-2");
        JsonNode report = reported(unitReport(projectors, "PJ-1", "Cracked", DAMAGED_FROM, null, null));
        decide(report, eventId, "{\"decision\":\"SHORT\"}").andExpect(status().isOk());
        setStatus(eventId, "confirmed");

        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment[0].quantity").value(1));
    }
}
