package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
@Tag("TS03")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentStatusFlowTest {

    private static final String START = "2027-03-10T01:00:00Z";
    private static final String END = "2027-03-10T04:00:00Z";
    private static final String LATER = "2027-03-10T06:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;
    private UUID mixers;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        mixers = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'TS03 Mixer', 3, true, 'A')
                """, mixers);
        for (String serialNumber : new String[] {"MX-1", "MX-2", "MX-3"}) {
            jdbc.update("""
                    INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                    VALUES (?, ?, 'AVAILABLE'::equipment_status)
                    """, mixers, serialNumber);
        }
    }

    private static String change(String status, String start, String end) {
        return "{\"status\":\"%s\",\"start\":\"%s\",\"end\":%s}"
                .formatted(status, start, end == null ? "null" : "\"" + end + "\"");
    }

    private ResultActions mark(String as, String serialNumber, String body) throws Exception {
        return mvc.perform(post("/api/equipment/" + mixers + "/units/" + serialNumber + "/periods")
                .with(flow.as(as)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void marked(String serialNumber, String status, String start, String end) throws Exception {
        mark("ts1", serialNumber, change(status, start, end)).andExpect(status().isNoContent());
        sync();
    }

    private ResultActions units(String as, String start, String end) throws Exception {
        return mvc.perform(get("/api/equipment/units").with(flow.as(as))
                .param("start", start).param("end", end));
    }

    private String statusOf(String serialNumber) {
        return "$[?(@.equipmentId == '%s' && @.serialNumber == '%s')].status".formatted(mixers, serialNumber);
    }

    private ResultActions periods(String serialNumber) throws Exception {
        return mvc.perform(get("/api/equipment/" + mixers + "/units/" + serialNumber + "/periods")
                .with(flow.as("ts1")));
    }

    private UUID onlyPeriodOf(String serialNumber) throws Exception {
        String response = periods(serialNumber).andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(response).get(0).get("id").asString());
    }

    private UUID eventRequestingMixers() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
        mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage setup\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":1}]}"
                                .formatted(mixers)))
                .andExpect(status().isCreated());
        sync();
        return eventId;
    }

    private UUID requestOf(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT request_id FROM equipment_requests WHERE event_id = ?", UUID.class, eventId);
    }

    private ResultActions availability(UUID requestId) throws Exception {
        return mvc.perform(get("/api/equipment/requests/" + requestId + "/availability").with(flow.as("ts1"))
                .param("start", START).param("end", END));
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int blocksFor(String serialNumber) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM equipment_status_periods WHERE equipment_id = ? AND serial_number = ?",
                Integer.class, mixers, serialNumber);
    }

    @Test
    void aUnitWithNoBlockShowsAsAvailableForThePeriod() throws Exception {
        units("ts1", START, END)
                .andExpect(status().isOk())
                .andExpect(jsonPath(statusOf("MX-1"), contains("Available")))
                .andExpect(jsonPath(statusOf("MX-2"), contains("Available")))
                .andExpect(jsonPath(statusOf("MX-3"), contains("Available")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Faulty", "Unavailable"})
    void markingAUnitSavesTheChangeAndTheUnitShowsThatStatusForThePeriod(String newStatus) throws Exception {
        mark("ts1", "MX-1", change(newStatus, START, END)).andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isEqualTo(1);
        units("ts1", START, END)
                .andExpect(jsonPath(statusOf("MX-1"), contains(newStatus)))
                .andExpect(jsonPath(statusOf("MX-2"), contains("Available")));
        periods("MX-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value(newStatus))
                .andExpect(jsonPath("$[0].start").value(START))
                .andExpect(jsonPath("$[0].end").value(END));
    }

    @Test
    void aBlockDoesNotChangeTheStatusShownForAdjacentPeriods() throws Exception {
        marked("MX-1", "Faulty", START, END);

        units("ts1", END, LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
        units("ts1", "2027-03-09T22:00:00Z", START).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
        units("ts1", "2027-03-10T03:59:00Z", LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Faulty")));
    }

    @Test
    void aBlockWithNoEndDateCoversEveryLaterPeriod() throws Exception {
        mark("ts1", "MX-1", change("Unavailable", START, null)).andExpect(status().isNoContent());
        sync();

        units("ts1", "2028-01-01T00:00:00Z", "2028-01-02T00:00:00Z")
                .andExpect(jsonPath(statusOf("MX-1"), contains("Unavailable")));
        periods("MX-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].start").value(START))
                .andExpect(jsonPath("$[0].end").isEmpty());
    }

    @Test
    void makingAUnitAvailableForPartOfABlockKeepsTheRestOfTheBlock() throws Exception {
        marked("MX-1", "Faulty", START, END);

        mark("ts1", "MX-1", change("Available", "2027-03-10T02:00:00Z", "2027-03-10T03:00:00Z"))
                .andExpect(status().isNoContent());
        sync();

        periods("MX-1")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("Faulty"))
                .andExpect(jsonPath("$[0].start").value(START))
                .andExpect(jsonPath("$[0].end").value("2027-03-10T02:00:00Z"))
                .andExpect(jsonPath("$[1].status").value("Faulty"))
                .andExpect(jsonPath("$[1].start").value("2027-03-10T03:00:00Z"))
                .andExpect(jsonPath("$[1].end").value(END));
        units("ts1", "2027-03-10T02:00:00Z", "2027-03-10T03:00:00Z")
                .andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
        units("ts1", START, "2027-03-10T02:00:00Z")
                .andExpect(jsonPath(statusOf("MX-1"), contains("Faulty")));
    }

    @Test
    void makingAUnitAvailableForTheWholeBlockRemovesTheBlock() throws Exception {
        marked("MX-1", "Faulty", START, END);

        mark("ts1", "MX-1", change("Available", START, END)).andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isZero();
        units("ts1", START, END).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    void removingABlockMakesTheUnitAvailableAgain() throws Exception {
        marked("MX-1", "Faulty", START, END);
        UUID periodId = onlyPeriodOf("MX-1");

        mvc.perform(delete("/api/equipment/periods/" + periodId).with(flow.as("ts1")))
                .andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isZero();
        units("ts1", START, END).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    void markingAUnitFaultyLowersTheAvailableQuantityAndMakingItAvailableRaisesItAgain() throws Exception {
        UUID requestId = requestOf(eventRequestingMixers());
        availability(requestId)
                .andExpect(jsonPath("$[0].totalQuantity").value(3))
                .andExpect(jsonPath("$[0].availableQuantity").value(3));

        marked("MX-1", "Faulty", START, END);
        availability(requestId)
                .andExpect(jsonPath("$[0].totalQuantity").value(3))
                .andExpect(jsonPath("$[0].availableQuantity").value(2));

        marked("MX-1", "Available", START, END);
        availability(requestId).andExpect(jsonPath("$[0].availableQuantity").value(3));
    }

    @Test
    void aUnitReservedForAnEventCannotBeMarkedFaultyForThatPeriodAndKeepsItsStatus() throws Exception {
        UUID eventId = eventRequestingMixers();
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,\"serialNumber\":\"MX-1\",\"start\":\"%s\",\"end\":\"%s\"}"
                                .formatted(eventId, mixers, START, END)))
                .andExpect(status().isCreated());
        sync();

        mark("ts1", "MX-1", change("Faulty", "2027-03-10T03:59:00Z", LATER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This unit is already committed to an event during that time."));

        assertThat(blocksFor("MX-1")).isZero();
        units("ts1", START, LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    void aReservedUnitCanBeMarkedFaultyForAPeriodThatStartsWhenItsReservationEnds() throws Exception {
        UUID eventId = eventRequestingMixers();
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,\"serialNumber\":\"MX-1\",\"start\":\"%s\",\"end\":\"%s\"}"
                                .formatted(eventId, mixers, START, END)))
                .andExpect(status().isCreated());
        sync();

        mark("ts1", "MX-1", change("Faulty", END, LATER)).andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isEqualTo(1);
    }

    @Test
    void aChangeWhoseEndIsNotAfterItsStartIsRefusedAndThePreviousStatusRemains() throws Exception {
        marked("MX-1", "Unavailable", START, END);

        mark("ts1", "MX-1", change("Faulty", START, START))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("start is required, and end must be after start"));

        assertThat(blocksFor("MX-1")).isEqualTo(1);
        periods("MX-1")
                .andExpect(jsonPath("$[0].status").value("Unavailable"))
                .andExpect(jsonPath("$[0].start").value(START))
                .andExpect(jsonPath("$[0].end").value(END));
        units("ts1", START, END).andExpect(jsonPath(statusOf("MX-1"), contains("Unavailable")));
    }

    @Test
    void aStatusThatIsNotOneOfTheThreeIsRefusedAndNothingIsSaved() throws Exception {
        mark("ts1", "MX-1", change("Broken", START, END)).andExpect(status().isBadRequest());

        assertThat(blocksFor("MX-1")).isZero();
    }

    @Test
    void aUnitThatDoesNotExistReturnsNotFound() throws Exception {
        mark("ts1", "MX-9", change("Faulty", START, END))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Equipment unit not found"));

        assertThat(blocksFor("MX-9")).isZero();
    }

    @Test
    void removingABlockThatDoesNotExistReturnsNotFound() throws Exception {
        mvc.perform(delete("/api/equipment/periods/" + UUID.randomUUID()).with(flow.as("ts1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Status period not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "vs1", "att1"})
    void otherRolesCannotChangeOrRemoveAStatusAndNothingIsSaved(String username) throws Exception {
        marked("MX-2", "Faulty", START, END);
        UUID periodId = onlyPeriodOf("MX-2");

        mark(username, "MX-1", change("Faulty", START, END)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/equipment/periods/" + periodId).with(flow.as(username)))
                .andExpect(status().isForbidden());

        assertThat(blocksFor("MX-1")).isZero();
        assertThat(blocksFor("MX-2")).isEqualTo(1);
    }

    @Test
    void aCoordinatorCanViewUnitStatus() throws Exception {
        marked("MX-1", "Faulty", START, END);

        units("ec1", START, END)
                .andExpect(status().isOk())
                .andExpect(jsonPath(statusOf("MX-1"), contains("Faulty")));
    }

    @Test
    void anUnauthenticatedCallerCannotChangeAStatusAndNothingIsSaved() throws Exception {
        mvc.perform(post("/api/equipment/" + mixers + "/units/MX-1/periods")
                        .contentType(MediaType.APPLICATION_JSON).content(change("Faulty", START, END)))
                .andExpect(status().isUnauthorized());

        assertThat(blocksFor("MX-1")).isZero();
    }
}
