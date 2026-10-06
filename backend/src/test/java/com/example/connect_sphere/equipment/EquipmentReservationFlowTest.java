package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@Tag("integration")
@Tag("TS02")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentReservationFlowTest {

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

    private void unit(UUID equipmentId, String serialNumber) {
        jdbc.update("""
                INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                VALUES (?, ?, 'AVAILABLE'::equipment_status)
                """, equipmentId, serialNumber);
    }

    private void block(UUID equipmentId, String serialNumber, String status, String from, String until) {
        jdbc.update("""
                INSERT INTO equipment_status_periods (equipment_id, serial_number, status, period_start, period_end)
                VALUES (?, ?, ?::equipment_status, ?::timestamptz, ?::timestamptz)
                """, equipmentId, serialNumber, status, from, until);
    }

    private UUID requested(UUID eventId, UUID equipmentId) throws Exception {
        String response = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage setup\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":1}]}"
                                .formatted(equipmentId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("requestId").asString());
    }

    private static String reservation(
            UUID eventId, UUID equipmentId, int quantity, String serialNumber, String start, String end) {
        return "{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,\"serialNumber\":%s,\"start\":\"%s\",\"end\":\"%s\"}"
                .formatted(eventId, equipmentId, quantity,
                        serialNumber == null ? "null" : "\"" + serialNumber + "\"", start, end);
    }

    private ResultActions reserve(String as, String body) throws Exception {
        return mvc.perform(post("/api/equipment/reservations").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void reserved(UUID eventId, UUID equipmentId, int quantity, String serialNumber) throws Exception {
        reserve("ts1", reservation(eventId, equipmentId, quantity, serialNumber, START, END))
                .andExpect(status().isCreated());
        sync();
    }

    private ResultActions availability(UUID requestId, String start, String end) throws Exception {
        return mvc.perform(get("/api/equipment/requests/" + requestId + "/availability").with(flow.as("ts1"))
                .param("start", start).param("end", end));
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int reservationsFor(UUID eventId) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class, eventId);
    }

    private String eventStatus(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void aTechnicianReservesAQuantityAndTheReservationIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 4, null, START, END))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logId").isNotEmpty())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.equipmentId").value(speakers.toString()))
                .andExpect(jsonPath("$.equipmentName").value("TS02 Speaker"))
                .andExpect(jsonPath("$.quantity").value(4))
                .andExpect(jsonPath("$.serialNumber").isEmpty())
                .andExpect(jsonPath("$.loanedFrom").value(START))
                .andExpect(jsonPath("$.loanedUntil").value(END));

        assertThat(reservationsFor(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM equipment_logs WHERE event_id = ? AND equipment_id = ?",
                Integer.class, eventId, speakers)).isEqualTo(4);
    }

    @Test
    void theReservationCanBeViewedForTheEventAfterSaving() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        reserved(eventId, speakers, 4, null);

        mvc.perform(get("/api/equipment/events/" + eventId + "/reservations").with(flow.as("ts1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].equipmentId").value(speakers.toString()))
                .andExpect(jsonPath("$[0].equipmentName").value("TS02 Speaker"))
                .andExpect(jsonPath("$[0].quantity").value(4))
                .andExpect(jsonPath("$[0].loanedFrom").value(START))
                .andExpect(jsonPath("$[0].loanedUntil").value(END));
    }

    @Test
    void availabilityListsOnlyRequestedEquipmentWithItsTotalAndAvailableQuantity() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        equipment("TS02 Not Requested", 5, false);
        UUID requestId = requested(eventId, speakers);

        availability(requestId, START, END)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].equipmentId").value(speakers.toString()))
                .andExpect(jsonPath("$[0].equipmentName").value("TS02 Speaker"))
                .andExpect(jsonPath("$[0].serialised").value(false))
                .andExpect(jsonPath("$[0].totalQuantity").value(10))
                .andExpect(jsonPath("$[0].availableQuantity").value(10));
    }

    @Test
    void aSavedReservationReducesLaterAvailabilityForThatPeriodOnly() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers);
        reserved(eventId, speakers, 4, null);

        availability(requestId, START, END)
                .andExpect(jsonPath("$[0].totalQuantity").value(10))
                .andExpect(jsonPath("$[0].availableQuantity").value(6));
        availability(requestId, END, "2027-03-10T06:00:00Z")
                .andExpect(jsonPath("$[0].availableQuantity").value(10));
    }

    @Test
    void reservingExactlyTheAvailableQuantityIsAccepted() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 10, null, START, END))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(10));
        sync();

        assertThat(reservationsFor(eventId)).isEqualTo(1);
        availability(requestId, START, END).andExpect(jsonPath("$[0].availableQuantity").value(0));
    }

    @Test
    void reservingOneMoreThanTheAvailableQuantityIsRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 11, null, START, END))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 10 of TS02 Speaker available for that period"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void quantityCommittedToAnOverlappingPeriodCannotBeReservedByAnotherEvent() throws Exception {
        UUID first = event("Town Hall");
        UUID second = event("Board Meeting");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(first, speakers);
        requested(second, speakers);
        reserved(first, speakers, 6, null);

        reserve("ts1", reservation(second, speakers, 5, null, "2027-03-10T03:59:00Z", "2027-03-10T06:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 4 of TS02 Speaker available for that period"));
        assertThat(reservationsFor(second)).isZero();
        assertThat(reservationsFor(first)).isEqualTo(1);
    }

    @Test
    void theQuantityLeftOverInAnOverlappingPeriodCanStillBeReserved() throws Exception {
        UUID first = event("Town Hall");
        UUID second = event("Board Meeting");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(first, speakers);
        requested(second, speakers);
        reserved(first, speakers, 6, null);

        reserve("ts1", reservation(second, speakers, 4, null, "2027-03-10T03:59:00Z", "2027-03-10T06:00:00Z"))
                .andExpect(status().isCreated());
        assertThat(reservationsFor(second)).isEqualTo(1);
    }

    @Test
    void aBackToBackPeriodIsNotAnOverlap() throws Exception {
        UUID first = event("Town Hall");
        UUID second = event("Board Meeting");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(first, speakers);
        requested(second, speakers);
        reserved(first, speakers, 10, null);

        reserve("ts1", reservation(second, speakers, 10, null, END, "2027-03-10T06:00:00Z"))
                .andExpect(status().isCreated());
        assertThat(reservationsFor(second)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAULTY", "UNAVAILABLE"})
    void aUnitMarkedFaultyOrUnavailableForThePeriodCannotBeReserved(String blockedStatus) throws Exception {
        UUID eventId = event("Town Hall");
        UUID mixers = equipment("TS02 Mixer", 3, true);
        unit(mixers, "MX-1");
        unit(mixers, "MX-2");
        unit(mixers, "MX-3");
        block(mixers, "MX-1", blockedStatus, "2027-03-10T03:00:00Z", "2027-03-11T00:00:00Z");
        requested(eventId, mixers);

        reserve("ts1", reservation(eventId, mixers, 1, "MX-1", START, END))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This unit is marked Faulty or Unavailable for part of that period"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void aBlockThatEndsWhenThePeriodStartsDoesNotPreventReservingTheUnit() throws Exception {
        UUID eventId = event("Town Hall");
        UUID mixers = equipment("TS02 Mixer", 3, true);
        unit(mixers, "MX-1");
        block(mixers, "MX-1", "FAULTY", "2027-03-09T00:00:00Z", START);
        requested(eventId, mixers);

        reserve("ts1", reservation(eventId, mixers, 1, "MX-1", START, END))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serialNumber").value("MX-1"));
        assertThat(reservationsFor(eventId)).isEqualTo(1);
    }

    @Test
    void blockedUnitsAreExcludedFromTheAvailableQuantity() throws Exception {
        UUID eventId = event("Town Hall");
        UUID mixers = equipment("TS02 Mixer", 3, true);
        unit(mixers, "MX-1");
        unit(mixers, "MX-2");
        unit(mixers, "MX-3");
        block(mixers, "MX-1", "FAULTY", START, END);
        UUID requestId = requested(eventId, mixers);

        availability(requestId, START, END)
                .andExpect(jsonPath("$[0].serialised").value(true))
                .andExpect(jsonPath("$[0].totalQuantity").value(3))
                .andExpect(jsonPath("$[0].availableQuantity").value(2));
        reserve("ts1", reservation(eventId, mixers, 3, null, START, END))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 2 of TS02 Mixer available for that period"));
        assertThat(reservationsFor(eventId)).isZero();
        reserve("ts1", reservation(eventId, mixers, 2, null, START, END))
                .andExpect(status().isCreated());
        assertThat(reservationsFor(eventId)).isEqualTo(1);
    }

    @Test
    void aUnitAlreadyReservedForAnOverlappingPeriodCannotBeReservedAgain() throws Exception {
        UUID first = event("Town Hall");
        UUID second = event("Board Meeting");
        UUID mixers = equipment("TS02 Mixer", 3, true);
        unit(mixers, "MX-1");
        requested(first, mixers);
        requested(second, mixers);
        reserved(first, mixers, 1, "MX-1");

        reserve("ts1", reservation(second, mixers, 1, "MX-1", "2027-03-10T03:59:00Z", "2027-03-10T06:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This unit is already reserved for an overlapping event"));
        assertThat(reservationsFor(second)).isZero();
        reserve("ts1", reservation(second, mixers, 1, "MX-1", END, "2027-03-10T06:00:00Z"))
                .andExpect(status().isCreated());
        assertThat(reservationsFor(second)).isEqualTo(1);
    }

    @Test
    void reservingEquipmentDoesNotConfirmTheEvent() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        assertThat(eventStatus(eventId)).isEqualTo("pending");

        reserved(eventId, speakers, 4, null);

        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "vs1", "att1"})
    void onlyTechnicalSupportCanReserveAndNothingIsSavedForOtherRoles(String username) throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve(username, reservation(eventId, speakers, 4, null, START, END))
                .andExpect(status().isForbidden());
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void anUnauthenticatedCallerCannotReserveAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        mvc.perform(post("/api/equipment/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(reservation(eventId, speakers, 4, null, START, END)))
                .andExpect(status().isUnauthorized());
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void equipmentThatIsNotPartOfTheEventsRequestCannotBeReserved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        UUID lights = equipment("TS02 Light", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, lights, 1, null, START, END))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This equipment is not part of an active request for the selected event."));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void equipmentThatDoesNotExistReturnsNotFoundAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, UUID.randomUUID(), 1, null, START, END))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Equipment not found"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void aQuantityOfZeroIsRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 0, null, START, END))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("quantity must be at least 1"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    void aPeriodThatEndsWhenItStartsIsRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 1, null, START, START))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("end must be after start"));
        assertThat(reservationsFor(eventId)).isZero();
    }
}
