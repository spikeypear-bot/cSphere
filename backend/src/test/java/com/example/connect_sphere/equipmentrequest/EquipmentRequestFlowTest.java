package com.example.connect_sphere.equipmentrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@Tag("integration")
@Tag("EC07")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("SO-C2")
class EquipmentRequestFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private UUID event() throws Exception {
        return flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
    }

    private UUID equipment(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, ?, 10, false, 'A')
                """, id, name);
        return id;
    }

    private static String item(UUID equipmentId, int quantity) {
        return "{\"equipmentId\":\"%s\",\"quantity\":%d}".formatted(equipmentId, quantity);
    }

    private static String body(String technicalRequirement, String... items) {
        return "{\"technicalRequirement\":\"%s\",\"items\":[%s]}"
                .formatted(technicalRequirement, String.join(",", items));
    }

    private ResultActions submit(UUID eventId, String as, String body) throws Exception {
        return mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID submitted(UUID eventId, String body) throws Exception {
        String response = submit(eventId, "ec1", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("requestId").asString());
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int requestsFor(UUID eventId) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM equipment_requests WHERE event_id = ?", Integer.class, eventId);
    }

    private int linesFor(UUID eventId) {
        sync();
        return jdbc.queryForObject("""
                SELECT count(*) FROM equipment_request_equipments l
                JOIN equipment_requests r ON r.request_id = l.request_id
                WHERE r.event_id = ?
                """, Integer.class, eventId);
    }

    private int savedQuantity(UUID requestId, UUID equipmentId) {
        return jdbc.queryForObject(
                "SELECT equipment_qty FROM equipment_request_equipments WHERE request_id = ? AND equipment_id = ?",
                Integer.class, requestId, equipmentId);
    }

    private String eventStatus(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    private void moveTo(UUID eventId, String status) {
        sync();
        jdbc.update("UPDATE events SET status = ?::event_status WHERE event_id = ?", status, eventId);
        sync();
    }

    private ResultActions revise(UUID eventId, String as, String body) throws Exception {
        return mvc.perform(patch("/api/events/" + eventId + "/equipment-request").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String statusOf(UUID requestId) {
        sync();
        return jdbc.queryForObject(
                "SELECT status::text FROM equipment_requests WHERE request_id = ?", String.class, requestId);
    }

    private int reservationsFor(UUID eventId) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class, eventId);
    }

    private void reserve(UUID eventId, UUID equipmentId, int quantity) throws Exception {
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":%d,"
                                .formatted(eventId, equipmentId, quantity)
                                // The event is created at 09:00-12:00+08:00 (FlowSupport.requestBody), i.e. 01:00-04:00Z.
                                + "\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                .andExpect(status().isCreated());
        sync();
    }

    private void approve(UUID requestId) throws Exception {
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk());
        sync();
    }

    @Test
    @DisplayName("EC07 (happy): the coordinator submits items with quantities and notes, and the request is saved as processing")
    void coordinatorSubmitsItemsWithQuantitiesAndNotesAndTheRequestIsSavedAsProcessing() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID microphone = equipment("EC07 Microphone");

        String response = submit(eventId, "ec1", body("Two screens on stage", item(projector, 2), item(microphone, 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.technicalRequirement").value("Two screens on stage"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].equipmentId").value(projector.toString()))
                .andExpect(jsonPath("$.lines[0].equipmentName").value("EC07 Projector"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[1].equipmentId").value(microphone.toString()))
                .andExpect(jsonPath("$.lines[1].equipmentName").value("EC07 Microphone"))
                .andExpect(jsonPath("$.lines[1].quantity").value(4))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(flow.read(response).get("requestId").asString());

        assertThat(requestsFor(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM equipment_requests WHERE request_id = ?", String.class, requestId))
                .isEqualTo("processing");
        assertThat(jdbc.queryForObject(
                "SELECT technical_requirement FROM equipment_requests WHERE request_id = ?", String.class, requestId))
                .isEqualTo("Two screens on stage");
        assertThat(linesFor(eventId)).isEqualTo(2);
        assertThat(savedQuantity(requestId, projector)).isEqualTo(2);
        assertThat(savedQuantity(requestId, microphone)).isEqualTo(4);
    }

    @Test
    @DisplayName("EC07 (happy): a submitted request appears in the Technical Support queue with its items")
    void aSubmittedRequestAppearsInTheTechnicalSupportQueueWithItsItems() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        mvc.perform(get("/api/equipment-requests/processing").with(flow.as("ts1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.requestId == '" + requestId + "')].eventId",
                        contains(eventId.toString())))
                .andExpect(jsonPath("$[?(@.requestId == '" + requestId + "')].eventName",
                        contains("Town Hall")))
                .andExpect(jsonPath("$[?(@.requestId == '" + requestId + "')].technicalRequirement",
                        contains("Two screens on stage")));
        mvc.perform(get("/api/equipment-requests/" + requestId + "/lines").with(flow.as("ts1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].equipmentId").value(projector.toString()))
                .andExpect(jsonPath("$[0].equipmentName").value("EC07 Projector"))
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    @DisplayName("EC07 (happy): the coordinator can view the request and its quantities after submitting")
    void theCoordinatorCanViewTheRequestAndItsQuantitiesAfterSubmitting() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].status").value("processing"))
                .andExpect(jsonPath("$[0].technicalRequirement").value("Two screens on stage"))
                .andExpect(jsonPath("$[0].lines.length()").value(1))
                .andExpect(jsonPath("$[0].lines[0].equipmentId").value(projector.toString()))
                .andExpect(jsonPath("$[0].lines[0].equipmentName").value("EC07 Projector"))
                .andExpect(jsonPath("$[0].lines[0].quantity").value(2));
        assertThat(requestsFor(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("EC07 (boundary): an event with no equipment request shows an empty list")
    void anEventWithNoRequestShowsAnEmptyList() throws Exception {
        UUID eventId = event();

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // SO-C2: the Safety Officer reads any active event's requests, with a real
    // safety_officer token, including once the event awaits its safety review.
    @ParameterizedTest
    @ValueSource(strings = {"pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved", "confirmed"})
    @DisplayName("SO-C2 (happy): the Safety Officer can view an active event's equipment requests, from Planning to Confirmed")
    void theSafetyOfficerCanViewTheRequestsOfAnActiveEvent(String eventStatus) throws Exception {
        UUID eventId = event();
        UUID requestId = submitted(eventId, body("Two screens on stage", item(equipment("EC07 Projector"), 2)));
        moveTo(eventId, eventStatus);

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("so1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].lines[0].quantity").value(2));
    }

    @Test
    @DisplayName("SO-C2 (negative): the Safety Officer cannot view the equipment requests of a cancelled event")
    void theSafetyOfficerCannotViewTheRequestsOfACancelledEvent() throws Exception {
        UUID eventId = event();
        submitted(eventId, body("Two screens on stage", item(equipment("EC07 Projector"), 2)));
        moveTo(eventId, "cancelled");

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("so1")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("EC07 (boundary): a quantity of one is accepted")
    void aQuantityOfOneIsAccepted() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        submit(eventId, "ec1", body("", item(projector, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].quantity").value(1));
        assertThat(requestsFor(eventId)).isEqualTo(1);
        assertThat(linesFor(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("EC07 (boundary): a quantity of zero is refused and nothing is saved")
    void aQuantityOfZeroIsRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID microphone = equipment("EC07 Microphone");

        submit(eventId, "ec1", body("Two screens on stage", item(microphone, 3), item(projector, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(requestsFor(eventId)).isZero();
        assertThat(linesFor(eventId)).isZero();
    }

    @Test
    @DisplayName("EC07 (negative): an equipment type that does not exist is refused with a message and nothing is saved")
    void anEquipmentTypeThatDoesNotExistIsRefusedWithAMessageAndNothingIsSaved() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        submit(eventId, "ec1", body("Two screens on stage", item(projector, 2), item(UUID.randomUUID(), 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(requestsFor(eventId)).isZero();
        assertThat(linesFor(eventId)).isZero();
    }

    @Test
    @DisplayName("EC07 (conflict): a second request is refused while one is processing, and the first is unchanged")
    void aSecondRequestIsRefusedWhileOneIsProcessingAndTheFirstIsUnchanged() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID microphone = equipment("EC07 Microphone");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        submit(eventId, "ec1", body("Changed my mind", item(microphone, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(requestsFor(eventId)).isEqualTo(1);
        assertThat(linesFor(eventId)).isEqualTo(1);
        assertThat(savedQuantity(requestId, projector)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT technical_requirement FROM equipment_requests WHERE request_id = ?", String.class, requestId))
                .isEqualTo("Two screens on stage");
    }

    @Test
    @DisplayName("EC07 (cross-cutting): submitting a request leaves the event's status unchanged")
    void submittingDoesNotChangeTheEventsStatus() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        assertThat(eventStatus(eventId)).isEqualTo("pending");

        submitted(eventId, body("Two screens on stage", item(projector, 2)));

        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("EC07 (negative): an event that is no longer being planned cannot take a request and nothing is saved")
    void anEventThatIsNoLongerInPlanningCannotTakeARequest() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        sync();
        jdbc.update("UPDATE events SET status = 'confirmed' WHERE event_id = ?", eventId);
        sync();

        submit(eventId, "ec1", body("Two screens on stage", item(projector, 2)))
                .andExpect(status().isConflict());
        assertThat(requestsFor(eventId)).isZero();
        assertThat(eventStatus(eventId)).isEqualTo("confirmed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "vs1", "ts1", "att1"})
    @DisplayName("EC07 (negative): other roles cannot submit or view a request and nothing is saved")
    void otherRolesCannotSubmitOrViewAndNothingIsSaved(String username) throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        submit(eventId, username, body("Two screens on stage", item(projector, 2)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as(username)))
                .andExpect(status().isForbidden());
        assertThat(requestsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("EC07 (negative): a coordinator not assigned to the event cannot submit or view a request and nothing is saved")
    void aCoordinatorNotAssignedToTheEventCannotSubmitOrViewAndNothingIsSaved() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        submit(eventId, "ec2", body("Two screens on stage", item(projector, 2)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        assertThat(requestsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("EC07 (negative): a caller who is not signed in cannot submit and nothing is saved")
    void anUnauthenticatedCallerCannotSubmitAndNothingIsSaved() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        mvc.perform(post("/api/events/" + eventId + "/equipment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Two screens on stage", item(projector, 2))))
                .andExpect(status().isUnauthorized());
        assertThat(requestsFor(eventId)).isZero();
    }

    @Test
    @DisplayName("EC07 (negative): submitting for an event that does not exist is not found and nothing is saved")
    void anEventThatDoesNotExistReturnsNotFound() throws Exception {
        UUID projector = equipment("EC07 Projector");
        UUID missing = UUID.randomUUID();

        submit(missing, "ec1", body("Two screens on stage", item(projector, 2)))
                .andExpect(status().isNotFound());
        assertThat(requestsFor(missing)).isZero();
    }

    @Test
    @DisplayName("EC07 (negative): a malformed event id is refused as a bad request")
    void aMalformedEventIdReturnsBadRequest() throws Exception {
        UUID projector = equipment("EC07 Projector");

        mvc.perform(post("/api/events/not-a-uuid/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Two screens on stage", item(projector, 2))))
                .andExpect(status().isBadRequest());
    }

    // ---- EC-NEW3: the coordinator revises the equipment request --------------

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (happy): revising a processing request supersedes it with a new processing request holding the new items")
    void revisingAProcessingRequestSupersedesItAndCreatesANewOneWithTheNewItems() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID microphone = equipment("EC07 Microphone");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        String response = revise(eventId, "ec1", body("Changed after the organiser's change", item(microphone, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].equipmentId").value(microphone.toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(5))
                .andReturn().getResponse().getContentAsString();
        UUID revisedId = UUID.fromString(flow.read(response).get("requestId").asString());

        assertThat(statusOf(requestId)).isEqualTo("superseded");
        assertThat(statusOf(revisedId)).isEqualTo("processing");
        assertThat(requestsFor(eventId)).isEqualTo(2);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (happy): revising an approved request keeps it and its reservations until Technical Support decide")
    void revisingAnApprovedRequestKeepsItsReservationsUntilTechnicalSupportDecide() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);
        assertThat(reservationsFor(eventId)).isEqualTo(1);

        UUID microphone = equipment("EC07 Microphone");
        revise(eventId, "ec1", body("Fewer screens, more microphones now", item(microphone, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.revision").value(true));

        assertThat(reservationsFor(eventId)).isEqualTo(1);
        assertThat(statusOf(requestId)).isEqualTo("approved");
    }

    private UUID revised(UUID eventId, String body) throws Exception {
        String response = revise(eventId, "ec1", body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("requestId").asString());
    }

    private void reject(UUID requestId) throws Exception {
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"rejected\",\"rejectReason\":\"Not enough stock\"}"))
                .andExpect(status().isOk());
        sync();
    }

    private int reservedQuantity(UUID eventId, UUID equipmentId) {
        sync();
        return jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM equipment_logs WHERE event_id = ? AND equipment_id = ?",
                Integer.class, eventId, equipmentId);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (happy): more of the same kind: Technical Support reserve only the extra, and approving keeps all of it")
    void aRevisionForMoreNeedsOnlyTheExtraReserved() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);

        UUID revision = revised(eventId, body("Three screens", item(projector, 3)));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":2,".formatted(eventId, projector)
                                + "\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                .andExpect(status().isConflict());
        reserve(eventId, projector, 1);
        approve(revision);

        assertThat(statusOf(requestId)).isEqualTo("superseded");
        assertThat(statusOf(revision)).isEqualTo("approved");
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(3);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (happy): fewer of a kind and a kind no longer wanted: approving releases exactly what is not needed")
    void approvingARevisionReleasesWhatItNoLongerNeeds() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID microphone = equipment("EC07 Microphone");
        UUID requestId = submitted(eventId, body("Screens and mics", item(projector, 3), item(microphone, 2)));
        reserve(eventId, projector, 3);
        reserve(eventId, microphone, 2);
        approve(requestId);

        UUID revision = revised(eventId, body("One screen only", item(projector, 1)));
        approve(revision);

        assertThat(statusOf(requestId)).isEqualTo("superseded");
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(1);
        assertThat(reservedQuantity(eventId, microphone)).isZero();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (happy): a new time: the old reservation is kept while the new one is made, and released on approval")
    void aTimeChangeRevisionReleasesTheOldWindowOnlyOnApproval() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);
        // The approved change moved the event an hour later: 02:00-05:00Z, overlapping the old loan.
        jdbc.update("UPDATE events SET start_datetime = '2027-03-10T02:00:00Z', end_datetime = '2027-03-10T05:00:00Z' WHERE event_id = ?", eventId);
        sync();

        UUID revision = revised(eventId, body("Two screens, new time", item(projector, 2)));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":2,".formatted(eventId, projector)
                                + "\"start\":\"2027-03-10T02:00:00Z\",\"end\":\"2027-03-10T05:00:00Z\"}"))
                .andExpect(status().isCreated());
        assertThat(reservationsFor(eventId)).isEqualTo(2);
        approve(revision);

        assertThat(jdbc.queryForList("SELECT loaned_from FROM equipment_logs WHERE event_id = ?", java.sql.Timestamp.class, eventId))
                .extracting(java.sql.Timestamp::toInstant).containsExactly(java.time.Instant.parse("2027-03-10T02:00:00Z"));
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(2);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (negative): Technical Support reject the revision; what they reserved for it goes back, the approved request and its reservations stay")
    void rejectingARevisionChangesNothingElse() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);

        UUID revision = revised(eventId, body("Five screens", item(projector, 5)));
        reserve(eventId, projector, 3);
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(5);
        reject(revision);

        assertThat(statusOf(revision)).isEqualTo("rejected");
        assertThat(statusOf(requestId)).isEqualTo("approved");
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE request_id = ?", Integer.class, revision))
                .isZero();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (boundary): all the stock is the event's at its old time; moved an hour later, the revision can still be booked, and approving gives the old units back")
    void aSmallTimeShiftCanReuseTheUnitsTheEventGivesUp() throws Exception {
        UUID eventId = event();
        UUID projector = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type) "
                + "VALUES (?, 'EC07 Only two', 2, false, 'A')", projector);
        UUID requestId = submitted(eventId, body("Both screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);
        jdbc.update("UPDATE events SET start_datetime = '2027-03-10T02:00:00Z', end_datetime = '2027-03-10T05:00:00Z' WHERE event_id = ?", eventId);
        sync();

        UUID revision = revised(eventId, body("Both screens, an hour later", item(projector, 2)));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":2,".formatted(eventId, projector)
                                + "\"start\":\"2027-03-10T02:00:00Z\",\"end\":\"2027-03-10T05:00:00Z\"}"))
                .andExpect(status().isCreated());
        approve(revision);

        assertThat(reservedQuantity(eventId, projector)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE event_id = ? AND request_id = ?",
                Integer.class, eventId, revision)).isEqualTo(1);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (conflict): another event's reservations are never treated as given up")
    void anotherEventsReservationsStillCountWhenBookingARevision() throws Exception {
        UUID eventId = event();
        UUID projector = UUID.randomUUID();
        jdbc.update("INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type) "
                + "VALUES (?, 'EC07 Only two shared', 2, false, 'A')", projector);
        UUID requestId = submitted(eventId, body("One screen", item(projector, 1)));
        reserve(eventId, projector, 1);
        approve(requestId);
        UUID other = event();
        UUID otherRequest = submitted(other, body("One screen", item(projector, 1)));
        reserve(other, projector, 1);
        approve(otherRequest);

        revised(eventId, body("Two screens", item(projector, 2)));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,".formatted(eventId, projector)
                                + "\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"))
                .andExpect(status().isConflict());
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(1);
        assertThat(reservedQuantity(other, projector)).isEqualTo(1);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (failure): approving a revision that is not fully reserved is refused and nothing is released")
    void anUnreservedRevisionCannotBeApprovedAndNothingIsReleased() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);

        UUID revision = revised(eventId, body("Four screens", item(projector, 4)));
        mvc.perform(patch("/api/equipment-requests/" + revision + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                .andExpect(status().isConflict());

        assertThat(statusOf(revision)).isEqualTo("processing");
        assertThat(statusOf(requestId)).isEqualTo("approved");
        assertThat(reservedQuantity(eventId, projector)).isEqualTo(2);
    }

    @Test
    @Tag("EC07")
    @DisplayName("EC07 (happy): the coordinator sees how many of each kind are free at the event's time, its own reservations included")
    void theCoordinatorSeesFreeStockAtTheEventsTime() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);
        UUID other = event();
        UUID otherRequest = submitted(other, body("Three screens", item(projector, 3)));
        reserve(other, projector, 3);
        approve(otherRequest);

        // 10 in stock, 3 held by another event at the same time; this event's own 2 count as free to it.
        mvc.perform(get("/api/events/" + eventId + "/equipment-availability").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.equipmentId == '%s')].free".formatted(projector)).value(7));
        mvc.perform(get("/api/events/" + eventId + "/equipment-availability").with(flow.as("ts1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (negative): revising an event that has no equipment request is refused and nothing is saved")
    void revisingAnEventWithNoEquipmentRequestIsRefused() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");

        revise(eventId, "ec1", body("First ever request", item(projector, 2)))
                .andExpect(status().isConflict());
        assertThat(requestsFor(eventId)).isZero();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (negative): a rejected request cannot be revised")
    void revisingAfterARejectionIsRefused() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));
        mvc.perform(patch("/api/equipment-requests/" + requestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"rejected\",\"rejectReason\":\"Not available\"}"))
                .andExpect(status().isOk());

        revise(eventId, "ec1", body("Try again differently", item(equipment("EC07 Microphone"), 1)))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (negative): a coordinator not assigned to the event cannot revise the request and nothing changes")
    void aCoordinatorNotAssignedToTheEventCannotReviseAndNothingChanges() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        revise(eventId, "ec2", body("Hijacked", item(equipment("EC07 Microphone"), 1)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(requestId)).isEqualTo("processing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "vs1", "ts1", "att1"})
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (negative): other roles cannot revise the request and nothing changes")
    void otherRolesCannotReviseAndNothingChanges(String username) throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        revise(eventId, username, body("Hijacked", item(equipment("EC07 Microphone"), 1)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(requestId)).isEqualTo("processing");
    }

    // ---- EC-NEW3 (D37): the coordinator marks a flagged request still suitable ----

    /** An approved request with a reservation, flagged as approving a change would flag it. */
    private UUID flagged(UUID eventId) throws Exception {
        UUID projector = equipment("D37 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));
        reserve(eventId, projector, 2);
        approve(requestId);
        jdbc.update("UPDATE equipment_requests SET status = 'attention', attention_reason = ? WHERE request_id = ?",
                "The event's equipment requirements text changed.", requestId);
        sync();
        return requestId;
    }

    private ResultActions markStillSuitable(UUID eventId, UUID requestId, String as, String note) throws Exception {
        return mvc.perform(patch("/api/events/" + eventId + "/equipment-request/" + requestId + "/still-suitable")
                .with(flow.as(as)).contentType(MediaType.APPLICATION_JSON)
                .content(note == null ? "{}" : "{\"note\":\"" + note + "\"}"));
    }

    private java.util.List<String> suitableEntries(UUID eventId) {
        sync();
        return jdbc.queryForList("""
                SELECT message FROM event_request_activity
                WHERE event_id = ? AND activity_type = 'equipment_request_marked_suitable'
                """, String.class, eventId);
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (D37) (happy): the coordinator marks a flagged request still suitable; it is approved again, with the note on the timeline")
    void markingAFlaggedRequestStillSuitableApprovesItAgain() throws Exception {
        UUID eventId = event();
        UUID requestId = flagged(eventId);

        markStillSuitable(eventId, requestId, "ec1", "Wording only, same projectors")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.attentionReason").doesNotExist());

        assertThat(statusOf(requestId)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT attention_reason FROM equipment_requests WHERE request_id = ?",
                String.class, requestId)).isNull();
        assertThat(suitableEntries(eventId)).containsExactly("Marked the equipment request still suitable: Wording only, same projectors");
        assertThat(reservationsFor(eventId)).isEqualTo(1);
    }

    @ParameterizedTest
    @Tag("EC-NEW3")
    @ValueSource(ints = {0, 2001})
    @DisplayName("EC-NEW3 (D37) (negative): a missing or over-long note is refused and the request stays flagged")
    void aMissingOrOverLongNoteIsRefused(int length) throws Exception {
        UUID eventId = event();
        UUID requestId = flagged(eventId);

        markStillSuitable(eventId, requestId, "ec1", length == 0 ? "   " : "x".repeat(length))
                .andExpect(status().isUnprocessableEntity());

        assertThat(statusOf(requestId)).isEqualTo("attention");
        assertThat(suitableEntries(eventId)).isEmpty();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (D37) (negative): a coordinator not assigned to the event cannot mark it still suitable")
    void anotherCoordinatorCannotMarkItStillSuitable() throws Exception {
        UUID eventId = event();
        UUID requestId = flagged(eventId);

        markStillSuitable(eventId, requestId, "ec2", "Looks fine").andExpect(status().isForbidden());

        assertThat(statusOf(requestId)).isEqualTo("attention");
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (D37) (negative): Technical Support cannot mark a flagged request still suitable")
    void technicalSupportCannotMarkItStillSuitable() throws Exception {
        UUID eventId = event();
        UUID requestId = flagged(eventId);

        markStillSuitable(eventId, requestId, "ts1", "Looks fine").andExpect(status().isForbidden());

        assertThat(statusOf(requestId)).isEqualTo("attention");
        assertThat(suitableEntries(eventId)).isEmpty();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("EC-NEW3 (D37) (conflict): a request that is not flagged cannot be marked still suitable")
    void aRequestNotFlaggedCannotBeMarkedStillSuitable() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("D37 Projector");
        UUID requestId = submitted(eventId, body("Two screens on stage", item(projector, 2)));

        markStillSuitable(eventId, requestId, "ec1", "Looks fine").andExpect(status().isConflict());

        assertThat(statusOf(requestId)).isEqualTo("processing");
        assertThat(suitableEntries(eventId)).isEmpty();
    }
}
