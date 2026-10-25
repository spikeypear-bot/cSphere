package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

/**
 * TS02 (decision-log D33): reserving a request's equipment in one
 * all-or-nothing batch, the cap at the requested quantity unless the extra
 * units are marked spare, and free units picked for a serialised kind
 * reserved without serial numbers. TS06 and TS09 read spares as reserved.
 * Real PostgreSQL and the real filter chain.
 */
@Tag("integration")
@Tag("TS02")
@Tag("TS06")
@Tag("TS09")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentBatchReservationFlowTest {

    private static final String START = "2027-03-10T01:00:00Z";
    private static final String END = "2027-03-10T04:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    // ---- fixtures -------------------------------------------------------------------

    private UUID event(String name) throws Exception {
        return flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
    }

    private UUID equipment(String name, int quantity, boolean serialised) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, ?, ?, ?, 'A')
                """, id, name, quantity, serialised);
        return id;
    }

    /** A serialised kind owning exactly these units, inserted in the order given. */
    private UUID units(String name, String... serials) {
        UUID id = equipment(name, serials.length, true);
        for (String serial : serials) {
            unit(id, serial);
        }
        return id;
    }

    private void unit(UUID equipmentId, String serialNumber) {
        jdbc.update("""
                INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                VALUES (?, ?, 'AVAILABLE'::equipment_status)
                """, equipmentId, serialNumber);
    }

    private void block(UUID equipmentId, String serialNumber) {
        jdbc.update("""
                INSERT INTO equipment_status_periods (equipment_id, serial_number, status, period_start, period_end)
                VALUES (?, ?, 'FAULTY'::equipment_status, ?::timestamptz, ?::timestamptz)
                """, equipmentId, serialNumber, START, END);
    }

    /** EC07: one processing request for the event, of these kinds and quantities. */
    private UUID requested(UUID eventId, Map<UUID, Integer> items) throws Exception {
        String lines = String.join(",", items.entrySet().stream()
                .map(item -> "{\"equipmentId\":\"%s\",\"quantity\":%d}".formatted(item.getKey(), item.getValue()))
                .toList());
        String response = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage\",\"items\":[" + lines + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("requestId").asString());
    }

    private static String line(UUID equipmentId, int quantity, Integer spare, String... serials) {
        String serialList = serials.length == 0 ? "null"
                : "[" + String.join(",", List.of(serials).stream().map(s -> "\"" + s + "\"").toList()) + "]";
        return "{\"equipmentId\":\"%s\",\"quantity\":%d,\"spare\":%s,\"serialNumbers\":%s}"
                .formatted(equipmentId, quantity, spare == null ? "null" : spare.toString(), serialList);
    }

    private ResultActions batch(String as, UUID eventId, String... lines) throws Exception {
        return mvc.perform(post("/api/equipment/reservations/batch").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"lines\":[%s]}"
                        .formatted(eventId, START, END, String.join(",", lines))));
    }

    private ResultActions single(UUID eventId, UUID equipmentId, int quantity, String serial, Boolean spare)
            throws Exception {
        return mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,\"serialNumber\":%s,\"start\":\"%s\",\"end\":\"%s\"%s}"
                        .formatted(eventId, equipmentId, quantity, serial == null ? "null" : "\"" + serial + "\"",
                                START, END, spare == null ? "" : ",\"spare\":" + spare)));
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int rowsFor(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class, eventId);
    }

    /** "serial:quantity:spare" per row of one kind for the event, by serial then spare. */
    private List<String> rowsOf(UUID eventId, UUID equipmentId) {
        sync();
        return jdbc.queryForList("""
                SELECT coalesce(serial_number, '-') || ':' || quantity || ':' || spare FROM equipment_logs
                WHERE event_id = ? AND equipment_id = ? ORDER BY serial_number NULLS FIRST, spare
                """, String.class, eventId, equipmentId);
    }

    // ---- batch ---------------------------------------------------------------------

    @Test
    @DisplayName("TS02 (happy): one batch reserves every requested item for the window: a quantity, and one row per serialised unit picked by serial number")
    void aBatchReservesEveryLineInOneCall() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        UUID mixers = units("D33 Mixer", "MX-3", "MX-1", "MX-2");
        requested(eventId, Map.of(speakers, 4, mixers, 2));

        batch("ts1", eventId, line(speakers, 4, null), line(mixers, 2, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].equipmentId").value(speakers.toString()))
                .andExpect(jsonPath("$[0].quantity").value(4))
                .andExpect(jsonPath("$[0].spare").value(false))
                .andExpect(jsonPath("$[0].loanedFrom").value(START))
                .andExpect(jsonPath("$[0].loanedUntil").value(END))
                .andExpect(jsonPath("$[1].serialNumber").value("MX-1"))
                .andExpect(jsonPath("$[2].serialNumber").value("MX-2"));

        assertThat(rowsOf(eventId, speakers)).containsExactly("-:4:false");
        assertThat(rowsOf(eventId, mixers)).containsExactly("MX-1:1:false", "MX-2:1:false");
    }

    @Test
    @DisplayName("TS02 (failure): when one line cannot be reserved the whole batch is refused, the error names that line's item, and nothing is saved")
    void aRefusedLineSavesNothing() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        UUID lights = equipment("D33 Light", 3, false);
        requested(eventId, Map.of(speakers, 4, lights, 5));

        batch("ts1", eventId, line(speakers, 4, null), line(lights, 5, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 3 of D33 Light available for that period"))
                .andExpect(jsonPath("$.equipmentId").value(lights.toString()));

        assertThat(rowsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (conflict): a batch line asking for more than the request is refused unless the extra is marked spare, and nothing is saved")
    void aLineAboveTheRequestedQuantityIsRefused() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        requested(eventId, Map.of(speakers, 2));

        batch("ts1", eventId, line(speakers, 3, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 2 more D33 Speaker can be reserved for this event: "
                        + "2 requested and 0 already reserved. Mark any extra units as spare."))
                .andExpect(jsonPath("$.equipmentId").value(speakers.toString()));
        assertThat(rowsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 TS06 (happy): extra units marked spare are reserved beside the requested ones, count towards approval, and the cap still holds afterwards")
    void sparesAreReservedBesideTheRequestedUnits() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        UUID requestId = requested(eventId, Map.of(speakers, 2));

        batch("ts1", eventId, line(speakers, 2, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].spare").value(false))
                .andExpect(jsonPath("$[1].quantity").value(1))
                .andExpect(jsonPath("$[1].spare").value(true));
        assertThat(rowsOf(eventId, speakers)).containsExactly("-:2:false", "-:1:true");

        // Exactly at the cap: one more requested unit is refused, one more spare is not.
        batch("ts1", eventId, line(speakers, 1, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 0 more D33 Speaker can be reserved for this event: "
                        + "2 requested and 2 already reserved. Mark any extra units as spare."));
        batch("ts1", eventId, line(speakers, 0, 1)).andExpect(status().isCreated());
        assertThat(rowsOf(eventId, speakers)).containsExactly("-:2:false", "-:1:true", "-:1:true");

        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    @DisplayName("TS02 (happy): named serial numbers are reserved as given, the first for the request and the rest as spares")
    void namedUnitsAreReservedWithTheExtraAsSpare() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID mixers = units("D33 Mixer", "MX-1", "MX-2", "MX-3");
        requested(eventId, Map.of(mixers, 1));

        batch("ts1", eventId, line(mixers, 1, 1, "MX-3", "MX-2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].serialNumber").value("MX-3"))
                .andExpect(jsonPath("$[0].spare").value(false))
                .andExpect(jsonPath("$[1].serialNumber").value("MX-2"))
                .andExpect(jsonPath("$[1].spare").value(true));
        assertThat(rowsOf(eventId, mixers)).containsExactly("MX-2:1:true", "MX-3:1:false");
    }

    @Test
    @DisplayName("TS02 (negative): serial numbers that do not match the units asked for are refused and nothing is saved")
    void serialNumbersMustMatchTheUnitCount() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID mixers = units("D33 Mixer", "MX-1", "MX-2", "MX-3");
        requested(eventId, Map.of(mixers, 2));

        batch("ts1", eventId, line(mixers, 2, null, "MX-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give one serial number per unit: 2 units but 1 serial numbers"))
                .andExpect(jsonPath("$.equipmentId").value(mixers.toString()));
        batch("ts1", eventId, line(mixers, 2, null, "MX-1", "MX-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each unit can be reserved only once"));
        assertThat(rowsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (conflict): picked units skip any that are Faulty or reserved by an overlapping event")
    void pickedUnitsAreFreeAndWorking() throws Exception {
        UUID other = event("Other Gala");
        UUID eventId = event("Batch Gala");
        UUID mixers = units("D33 Mixer", "MX-1", "MX-2", "MX-3", "MX-4");
        block(mixers, "MX-1");
        requested(other, Map.of(mixers, 1));
        batch("ts1", other, line(mixers, 1, null, "MX-2")).andExpect(status().isCreated());
        requested(eventId, Map.of(mixers, 2));

        batch("ts1", eventId, line(mixers, 2, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].serialNumber").value("MX-3"))
                .andExpect(jsonPath("$[1].serialNumber").value("MX-4"));
        assertThat(rowsOf(eventId, mixers)).containsExactly("MX-3:1:false", "MX-4:1:false");
    }

    @Test
    @DisplayName("TS02 (boundary): when fewer working units are free than asked for, picking is refused and nothing is saved")
    void notEnoughFreeUnitsIsRefused() throws Exception {
        UUID eventId = event("Batch Gala");
        // Three owned but only two registered as units: availability allows 3, the units do not.
        UUID mixers = equipment("D33 Mixer", 3, true);
        unit(mixers, "MX-1");
        unit(mixers, "MX-2");
        requested(eventId, Map.of(mixers, 3));

        batch("ts1", eventId, line(mixers, 3, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 2 working D33 Mixer units are free for that period"))
                .andExpect(jsonPath("$.equipmentId").value(mixers.toString()));
        assertThat(rowsFor(eventId)).isZero();

        batch("ts1", eventId, line(mixers, 2, null)).andExpect(status().isCreated());
        assertThat(rowsOf(eventId, mixers)).containsExactly("MX-1:1:false", "MX-2:1:false");
    }

    @Test
    @DisplayName("TS02 (negative): an item that is not in the request, an unknown item, a repeated item or no lines are refused and nothing is saved")
    void invalidLinesAreRefused() throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        UUID lights = equipment("D33 Light", 10, false);
        UUID unknown = UUID.randomUUID();
        requested(eventId, Map.of(speakers, 2));

        batch("ts1", eventId, line(speakers, 1, null), line(lights, 1, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This equipment is not part of an active request for the selected event."))
                .andExpect(jsonPath("$.equipmentId").value(lights.toString()));
        batch("ts1", eventId, line(unknown, 1, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.equipmentId").value(unknown.toString()));
        batch("ts1", eventId, line(speakers, 1, null), line(speakers, 1, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each item can appear only once in a reservation"));
        batch("ts1", eventId, line(speakers, 0, 0))
                .andExpect(status().isBadRequest());
        batch("ts1", eventId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose at least one item to reserve"));
        assertThat(rowsFor(eventId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "ecl1", "vs1", "att1"})
    @DisplayName("TS02 (negative): only Technical Support can reserve a batch; any other role gets 403 and nothing is saved")
    void onlyTechnicalSupportCanReserveABatch(String username) throws Exception {
        UUID eventId = event("Batch Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        requested(eventId, Map.of(speakers, 2));

        batch(username, eventId, line(speakers, 2, null)).andExpect(status().isForbidden());
        assertThat(rowsFor(eventId)).isZero();
    }

    // ---- the single endpoint ---------------------------------------------------------

    @Test
    @DisplayName("TS02 (conflict): one reservation above the requested quantity is refused unless marked spare")
    void theSingleEndpointHasTheSameCap() throws Exception {
        UUID eventId = event("Single Gala");
        UUID speakers = equipment("D33 Speaker", 10, false);
        requested(eventId, Map.of(speakers, 2));

        single(eventId, speakers, 3, null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 2 more D33 Speaker can be reserved for this event: "
                        + "2 requested and 0 already reserved. Mark any extra units as spare."));
        assertThat(rowsFor(eventId)).isZero();

        single(eventId, speakers, 2, null, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.spare").value(false));
        single(eventId, speakers, 1, null, false).andExpect(status().isConflict());
        single(eventId, speakers, 1, null, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.spare").value(true));
        assertThat(rowsOf(eventId, speakers)).containsExactly("-:2:false", "-:1:true");
    }

    @Test
    @DisplayName("TS02 (happy): one reservation of a serialised kind without a serial number picks free units, one row each")
    void theSingleEndpointPicksUnits() throws Exception {
        UUID eventId = event("Single Gala");
        UUID mixers = units("D33 Mixer", "MX-2", "MX-1", "MX-3");
        block(mixers, "MX-1");
        requested(eventId, Map.of(mixers, 2));

        single(eventId, mixers, 2, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serialNumber").value("MX-2"))
                .andExpect(jsonPath("$.quantity").value(1));
        assertThat(rowsOf(eventId, mixers)).containsExactly("MX-2:1:false", "MX-3:1:false");
    }

    // ---- TS09 reads spares --------------------------------------------------------------

    @Test
    @DisplayName("TS09 (happy): a spare unit reserved beside the requested one covers it when the requested unit is reported damaged")
    void aSpareCoversADamagedUnit() throws Exception {
        UUID eventId = event("Spare Gala");
        UUID projectors = units("D33 Projector", "PJ-1", "PJ-2");
        UUID requestId = requested(eventId, Map.of(projectors, 1));
        batch("ts1", eventId, line(projectors, 1, 1)).andExpect(status().isCreated());
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk());
        sync();

        mvc.perform(post("/api/equipment-damage-reports").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentId\":\"%s\",\"serialNumber\":\"PJ-1\",\"description\":\"Lamp failed\",\"periodStart\":\"2027-03-09T00:00:00Z\",\"periodEnd\":null}"
                                .formatted(projectors)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affectedEvents.length()").value(1))
                .andExpect(jsonPath("$.affectedEvents[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.affectedEvents[0].reservedQuantity").value(2))
                .andExpect(jsonPath("$.affectedEvents[0].spareCovers").value(true));
    }
}
