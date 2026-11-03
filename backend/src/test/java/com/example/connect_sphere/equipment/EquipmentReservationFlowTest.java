package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
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
@Tag("TS06")
@Tag("EC-NEW3")
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

    // D33: reservations are capped at the requested quantity unless marked
    // spare, so the default request asks for enough (10) for every
    // reservation these tests make; the TS06 tests name their quantity.
    private UUID requested(UUID eventId, UUID equipmentId) throws Exception {
        return requested(eventId, equipmentId, 10);
    }

    private UUID requested(UUID eventId, UUID equipmentId, int quantity) throws Exception {
        String response = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage setup\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":%d}]}"
                                .formatted(equipmentId, quantity)))
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

    private ResultActions updateRequestStatus(String as, UUID requestId, String body) throws Exception {
        return mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as(as))
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
    @DisplayName("TS02 (happy): Technical Support reserve a quantity for the event and the reservation is saved")
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
    @DisplayName("TS02 (happy): a saved reservation can be viewed for the event")
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
    @DisplayName("TS02 (happy): availability lists only the requested equipment, with its total and available quantity")
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
    @DisplayName("TS02 (happy): a saved reservation lowers availability for its own period only")
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
    @DisplayName("TS02 (boundary): reserving one more than the available quantity is refused and nothing is saved")
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
    @DisplayName("TS02 (conflict): quantity already reserved for an overlapping period cannot be reserved by another event")
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
    @DisplayName("TS02 (conflict): the quantity left over in an overlapping period can still be reserved")
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
    @DisplayName("TS02 (boundary): a period that starts when another ends is not an overlap and can be fully reserved")
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
    @DisplayName("TS02 (conflict): a unit marked Faulty or Unavailable for part of the period cannot be reserved and nothing is saved")
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
    @DisplayName("TS02 (boundary): a block that ends exactly when the period starts does not stop the unit being reserved")
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
    @DisplayName("TS02 (negative): blocked units are left out of the available quantity and cannot be reserved")
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
        // D33: a serialised kind is one reservation per unit, here the two working ones.
        assertThat(reservationsFor(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("TS02 (conflict): a unit already reserved for an overlapping period cannot be reserved again, but can be for a later period")
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
    @DisplayName("TS02 (cross-cutting): reserving equipment leaves the event pending, not confirmed")
    void reservingEquipmentDoesNotConfirmTheEvent() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        assertThat(eventStatus(eventId)).isEqualTo("pending");

        reserved(eventId, speakers, 4, null);

        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS06 (failure): approval is refused until every requested quantity is reserved, and nothing changes")
    void approvalIsRejectedUntilEveryRequestedQuantityIsReservedAndChangesNothing() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS06 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers, 2);
        reserved(eventId, speakers, 1, null);

        updateRequestStatus("ts1", requestId, "{\"status\":\"approved\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Cannot approve: only 1 of 2 TS06 Speaker are reserved for the full event period."));

        sync();
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM equipment_requests WHERE request_id = ?",
                String.class, requestId)).isEqualTo("processing");
        assertThat(reservationsFor(eventId)).isEqualTo(1);
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS06 (boundary): approval is refused when reservations do not cover the whole event, and reservations and event stay unchanged")
    void approvalRequiresReservationsToCoverTheFullEventAndLeavesReservationsAndEventUnchanged()
            throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS06 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers, 1);
        reserve("ts1", reservation(
                eventId, speakers, 1, null, "2027-03-10T02:00:00Z", END))
                .andExpect(status().isCreated());

        updateRequestStatus("ts1", requestId, "{\"status\":\"approved\"}")
                .andExpect(status().isConflict());
        assertThat(reservationsFor(eventId)).isEqualTo(1);
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS06 (happy): a fully reserved request can be approved and the coordinator sees it approved straight away")
    void fullyReservedRequestCanBeApprovedAndTheCoordinatorSeesItImmediately() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS06 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers, 1);
        reserved(eventId, speakers, 1, null);

        updateRequestStatus("ts1", requestId, "{\"status\":\"approved\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.rejectReason").isEmpty());

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("approved"));
        assertThat(reservationsFor(eventId)).isEqualTo(1);
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS06 EC-NEW3 (negative): rejecting needs a reason, the coordinator sees it, and the request's reservations are given back")
    void rejectionRequiresAndPersistsAReasonForTheCoordinator() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS06 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers, 1);
        reserved(eventId, speakers, 1, null);

        updateRequestStatus("ts1", requestId, "{\"status\":\"rejected\"}")
                .andExpect(status().isBadRequest());
        updateRequestStatus("ts1", requestId,
                        "{\"status\":\"rejected\",\"rejectReason\":\"  No suitable units  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectReason").value("No suitable units"));

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("rejected"))
                .andExpect(jsonPath("$[0].rejectReason").value("No suitable units"));
        // EC-NEW3 (D40): a rejected request gives back what was reserved for it.
        assertThat(reservationsFor(eventId)).isZero();
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("TS06 (negative): only Technical Support can change an equipment request's status, and nothing changes for a coordinator")
    void onlyTechnicalSupportCanChangeEquipmentRequestStatus() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS06 Speaker", 10, false);
        UUID requestId = requested(eventId, speakers);

        updateRequestStatus("ec1", requestId,
                        "{\"status\":\"rejected\",\"rejectReason\":\"Not available\"}")
                .andExpect(status().isForbidden());

        sync();
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM equipment_requests WHERE request_id = ?",
                String.class, requestId)).isEqualTo("processing");
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "vs1", "att1"})
    @DisplayName("TS02 (negative): other roles cannot reserve equipment and nothing is saved")
    void onlyTechnicalSupportCanReserveAndNothingIsSavedForOtherRoles(String username) throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve(username, reservation(eventId, speakers, 4, null, START, END))
                .andExpect(status().isForbidden());
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (negative): a caller who is not signed in cannot reserve and nothing is saved")
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
    @DisplayName("TS02 (negative): equipment that is not on the event's request cannot be reserved and nothing is saved")
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
    @DisplayName("TS02 (negative): equipment that does not exist is not found and nothing is saved")
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
    @DisplayName("TS02 (boundary): a quantity of zero is refused and nothing is saved")
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
    @DisplayName("TS02 (boundary): a period that ends when it starts is refused and nothing is saved")
    void aPeriodThatEndsWhenItStartsIsRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);

        reserve("ts1", reservation(eventId, speakers, 1, null, START, START))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("end must be after start"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    private ResultActions reserveBatch(String body) throws Exception {
        return mvc.perform(post("/api/equipment/reservations/batch").with(flow.as("ts1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("TS02 (negative): a reservation without its event, its equipment, or a valid period is refused and nothing is saved")
    void aReservationNeedsItsEventEquipmentAndPeriod() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        for (String body : List.of(
                "{\"eventId\":\"%s\",\"quantity\":1,\"start\":\"%s\",\"end\":\"%s\"}".formatted(eventId, START, END),
                "{\"equipmentId\":\"%s\",\"quantity\":1,\"start\":\"%s\",\"end\":\"%s\"}".formatted(speakers, START, END),
                "{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,\"end\":\"%s\"}".formatted(eventId, speakers, END),
                "{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,\"start\":\"%s\"}".formatted(eventId, speakers, START)))
            reserve("ts1", body).andExpect(status().isBadRequest());
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (negative): a specific serial number reserves exactly one unit; asking for two with it is refused and nothing is saved")
    void aSerialNumberReservesOneUnit() throws Exception {
        UUID eventId = event("Town Hall");
        UUID cameras = equipment("TS02 Camera", 3, true);
        unit(cameras, "CAM-001");
        requested(eventId, cameras);
        reserve("ts1", reservation(eventId, cameras, 2, "CAM-001", START, END))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A specific serial number reserves exactly 1 unit"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (negative): a serial number that is not one of the kind's units is not found, and nothing is saved")
    void anUnknownSerialNumberIsNotFound() throws Exception {
        UUID eventId = event("Town Hall");
        UUID cameras = equipment("TS02 Camera", 3, true);
        unit(cameras, "CAM-001");
        requested(eventId, cameras);
        reserve("ts1", reservation(eventId, cameras, 1, "CAM-999", START, END))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Equipment unit not found"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (negative): serial numbers given for a kind that is not tracked by serial number are refused, and nothing is saved")
    void serialNumbersNeedASerialisedKind() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        reserveBatch("{\"eventId\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"lines\":[{\"equipmentId\":\"%s\",\"quantity\":1,\"serialNumbers\":[\"SPK-1\"]}]}"
                .formatted(eventId, START, END, speakers))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("TS02 Speaker is not tracked by serial number"));
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (negative): a batch without its event or items, or with an empty, unnamed, negative or blank-serial line, is refused and nothing is saved")
    void aBatchNeedsWellFormedLines() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers);
        String window = "\"start\":\"%s\",\"end\":\"%s\"".formatted(START, END);
        String event = "\"eventId\":\"%s\",".formatted(eventId);
        String kind = "\"equipmentId\":\"%s\"".formatted(speakers);
        for (String body : List.of(
                "{" + window + ",\"lines\":[{" + kind + ",\"quantity\":1}]}",
                "{" + event + window + "}",
                "{" + event + window + ",\"lines\":[null]}",
                "{" + event + window + ",\"lines\":[{\"quantity\":1}]}",
                "{" + event + window + ",\"lines\":[{" + kind + ",\"quantity\":-1,\"spare\":2}]}",
                "{" + event + window + ",\"lines\":[{" + kind + ",\"quantity\":2,\"spare\":-1}]}",
                "{" + event + window + ",\"lines\":[{" + kind + ",\"quantity\":1,\"serialNumbers\":[\" \"]}]}",
                "{" + event + window + ",\"lines\":[{" + kind + ",\"quantity\":1,\"serialNumbers\":[null]}]}"))
            reserveBatch(body).andExpect(status().isBadRequest());
        assertThat(reservationsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("TS02 (boundary): a batch line with only spare units, its quantity left out, is saved as spare units")
    void aBatchLineCanBeSpareOnly() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 10, false);
        requested(eventId, speakers, 1);
        reserveBatch("{\"eventId\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"lines\":[{\"equipmentId\":\"%s\",\"spare\":2}]}"
                .formatted(eventId, START, END, speakers))
                .andExpect(status().isCreated());
        sync();
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM equipment_logs WHERE event_id = ? AND spare", Integer.class, eventId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("TS02 (negative): a kind's reservations are only listed for a period with both ends, the end after the start")
    void aKindsReservationsNeedAValidPeriod() throws Exception {
        UUID speakers = equipment("TS02 Speaker", 10, false);
        for (String[] range : List.of(new String[] {null, END}, new String[] {START, null}, new String[] {END, START}, new String[] {START, START})) {
            var get = get("/api/equipment/" + speakers + "/reservations").with(flow.as("ts1"));
            if (range[0] != null) get = get.param("from", range[0]);
            if (range[1] != null) get = get.param("to", range[1]);
            mvc.perform(get).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/equipment/" + speakers + "/reservations").with(flow.as("ts1")).param("from", START).param("to", END))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("TS02 (boundary): availability for a request with no items lists nothing")
    void availabilityOfARequestWithoutItemsIsEmpty() throws Exception {
        availability(UUID.randomUUID(), START, END).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("EC-NEW3 TS02 (happy): for a revision after the event moved, units the event holds only at its old time count as free, and units still covering the event count towards the new quantity")
    void aRevisionReusesUnitsHeldAtTheOldTime() throws Exception {
        UUID eventId = event("Town Hall");
        UUID speakers = equipment("TS02 Speaker", 6, false);
        UUID screens = equipment("TS02 Screen", 5, false);
        UUID old = requested(eventId, speakers, 6);
        reserved(eventId, speakers, 2, null);
        reserved(eventId, speakers, 1, null);
        reserved(eventId, speakers, 3, null);
        updateRequestStatus("ts1", old, "{\"status\":\"approved\"}").andExpect(status().isOk());
        sync();
        // The event moved: the 2-unit reservation is now an hour off, the 3-unit one wholly before it; 1 still covers it.
        jdbc.update("UPDATE equipment_logs SET loaned_from = loaned_from - interval '1 hour', loaned_until = loaned_until - interval '1 hour' "
                + "WHERE event_id = ? AND quantity = 2", eventId);
        jdbc.update("UPDATE equipment_logs SET loaned_from = loaned_from - interval '6 hours', loaned_until = loaned_until - interval '6 hours' "
                + "WHERE event_id = ? AND quantity = 3", eventId);
        String revised = mvc.perform(patch("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage setup\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":3},{\"equipmentId\":\"%s\",\"quantity\":1}]}"
                                .formatted(speakers, screens)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        sync();
        UUID revision = UUID.fromString(flow.read(revised).get("requestId").asString());

        // 6 speakers: 2 overlap the window at the old time and 1 covers it, so 3 are free; the 2 given up count as free too.
        // The 3 wholly before the window take nothing from it, and are not counted twice.
        availability(revision, START, END).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.equipmentId == '" + speakers + "')].availableQuantity").value(5));
        // 3 asked for and 1 already covering the event: 2 more may be reserved, not 3.
        reserve("ts1", reservation(eventId, speakers, 3, null, START, END)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Only 2 more TS02 Speaker")));
        // One at a time: the second sees the first as the revision's own, not one it gives up.
        reserved(eventId, speakers, 1, null);
        reserved(eventId, speakers, 1, null);
        reserved(eventId, screens, 1, null);
        updateRequestStatus("ts1", revision, "{\"status\":\"approved\"}").andExpect(status().isOk());
        sync();
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM equipment_logs WHERE event_id = ? AND equipment_id = ?",
                Integer.class, eventId, speakers)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id = ?", String.class, old))
                .isEqualTo("superseded");
    }
}
