package com.example.connect_sphere.equipmentrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
@Tag("EC07")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
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

    @Test
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
    void anEventWithNoRequestShowsAnEmptyList() throws Exception {
        UUID eventId = event();

        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
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
    void submittingDoesNotChangeTheEventsStatus() throws Exception {
        UUID eventId = event();
        UUID projector = equipment("EC07 Projector");
        assertThat(eventStatus(eventId)).isEqualTo("pending");

        submitted(eventId, body("Two screens on stage", item(projector, 2)));

        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
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
    void anEventThatDoesNotExistReturnsNotFound() throws Exception {
        UUID projector = equipment("EC07 Projector");
        UUID missing = UUID.randomUUID();

        submit(missing, "ec1", body("Two screens on stage", item(projector, 2)))
                .andExpect(status().isNotFound());
        assertThat(requestsFor(missing)).isZero();
    }

    @Test
    void aMalformedEventIdReturnsBadRequest() throws Exception {
        UUID projector = equipment("EC07 Projector");

        mvc.perform(post("/api/events/not-a-uuid/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Two screens on stage", item(projector, 2))))
                .andExpect(status().isBadRequest());
    }
}
