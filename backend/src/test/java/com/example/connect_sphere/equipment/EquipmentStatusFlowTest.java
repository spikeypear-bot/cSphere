package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @DisplayName("TS03 (happy): a unit with no status change shows as Available for the period")
    void aUnitWithNoBlockShowsAsAvailableForThePeriod() throws Exception {
        units("ts1", START, END)
                .andExpect(status().isOk())
                .andExpect(jsonPath(statusOf("MX-1"), contains("Available")))
                .andExpect(jsonPath(statusOf("MX-2"), contains("Available")))
                .andExpect(jsonPath(statusOf("MX-3"), contains("Available")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Faulty", "Unavailable"})
    @DisplayName("TS03 (happy): marking a unit Faulty or Unavailable is saved and the unit shows that status for the period")
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
    @DisplayName("TS03 (boundary): a status change does not affect the periods just before and after it")
    void aBlockDoesNotChangeTheStatusShownForAdjacentPeriods() throws Exception {
        marked("MX-1", "Faulty", START, END);

        units("ts1", END, LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
        units("ts1", "2027-03-09T22:00:00Z", START).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
        units("ts1", "2027-03-10T03:59:00Z", LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Faulty")));
    }

    @Test
    @DisplayName("TS03 (boundary): a status change with no end date covers every later period")
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
    @DisplayName("TS03 (happy): marking a unit Available for the whole blocked period removes the block")
    void makingAUnitAvailableForTheWholeBlockRemovesTheBlock() throws Exception {
        marked("MX-1", "Faulty", START, END);

        mark("ts1", "MX-1", change("Available", START, END)).andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isZero();
        units("ts1", START, END).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    @DisplayName("TS03 (happy): removing a status period makes the unit Available again")
    void removingABlockMakesTheUnitAvailableAgain() throws Exception {
        marked("MX-1", "Faulty", START, END);
        UUID periodId = onlyPeriodOf("MX-1");

        mvc.perform(delete("/api/equipment/periods/" + periodId).with(flow.as("ts1")))
                .andExpect(status().isNoContent());

        assertThat(blocksFor("MX-1")).isZero();
        units("ts1", START, END).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    @DisplayName("TS03 (happy): marking a unit Faulty lowers the available quantity, and marking it Available raises it again")
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
    @DisplayName("TS03 (conflict): a unit reserved for an event cannot be marked Faulty for that time and keeps its status")
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
    @DisplayName("TS03 (boundary): a reserved unit can be marked Faulty from the moment its reservation ends")
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
    @DisplayName("TS03 (boundary): a change whose end is not after its start is refused and the previous status stays")
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
    @DisplayName("TS03 (negative): a status other than Available, Faulty or Unavailable is refused and nothing is saved")
    void aStatusThatIsNotOneOfTheThreeIsRefusedAndNothingIsSaved() throws Exception {
        mark("ts1", "MX-1", change("Broken", START, END)).andExpect(status().isBadRequest());

        assertThat(blocksFor("MX-1")).isZero();
    }

    @Test
    @DisplayName("TS03 (negative): a unit that does not exist is not found and nothing is saved")
    void aUnitThatDoesNotExistReturnsNotFound() throws Exception {
        mark("ts1", "MX-9", change("Faulty", START, END))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Equipment unit not found"));

        assertThat(blocksFor("MX-9")).isZero();
    }

    @Test
    @DisplayName("TS03 (negative): removing a status period that does not exist is not found")
    void removingABlockThatDoesNotExistReturnsNotFound() throws Exception {
        mvc.perform(delete("/api/equipment/periods/" + UUID.randomUUID()).with(flow.as("ts1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Status period not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "vs1", "att1"})
    @DisplayName("TS03 (negative): other roles cannot change or remove a unit's status and nothing is saved")
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
    @DisplayName("TS03 (happy): a coordinator can view a unit's status")
    void aCoordinatorCanViewUnitStatus() throws Exception {
        marked("MX-1", "Faulty", START, END);

        units("ec1", START, END)
                .andExpect(status().isOk())
                .andExpect(jsonPath(statusOf("MX-1"), contains("Faulty")));
    }

    @Test
    @DisplayName("TS03 (negative): a caller who is not signed in cannot change a status and nothing is saved")
    void anUnauthenticatedCallerCannotChangeAStatusAndNothingIsSaved() throws Exception {
        mvc.perform(post("/api/equipment/" + mixers + "/units/MX-1/periods")
                        .contentType(MediaType.APPLICATION_JSON).content(change("Faulty", START, END)))
                .andExpect(status().isUnauthorized());

        assertThat(blocksFor("MX-1")).isZero();
    }

    @Test
    @DisplayName("TS03 (negative): a change with no status or no start is refused and nothing is saved")
    void aChangeNeedsAStatusAndAStart() throws Exception {
        mark("ts1", "MX-1", "{\"start\":\"" + START + "\",\"end\":\"" + END + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status is required"));
        mark("ts1", "MX-1", "{\"status\":\"Faulty\",\"end\":\"" + END + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("start is required, and end must be after start"));

        assertThat(blocksFor("MX-1")).isZero();
    }

    @Test
    @DisplayName("TS03 (boundary): marking a unit Available part-way through a block with no end keeps it before, and again with no end after")
    void availableInsideAnOpenEndedBlockSplitsIt() throws Exception {
        marked("MX-1", "Faulty", START, null);

        marked("MX-1", "Available", END, LATER);

        periods("MX-1")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("Faulty"))
                .andExpect(jsonPath("$[0].start").value(START))
                .andExpect(jsonPath("$[0].end").value(END))
                .andExpect(jsonPath("$[1].status").value("Faulty"))
                .andExpect(jsonPath("$[1].start").value(LATER))
                .andExpect(jsonPath("$[1].end").isEmpty());
        units("ts1", END, LATER).andExpect(jsonPath(statusOf("MX-1"), contains("Available")));
    }

    @Test
    @DisplayName("TS03 (boundary): a new block with no end replaces the rest of an earlier block from its start")
    void anOpenEndedBlockReplacesTheRestOfAnEarlierOne() throws Exception {
        marked("MX-1", "Unavailable", START, LATER);

        marked("MX-1", "Faulty", END, null);

        periods("MX-1")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("Unavailable"))
                .andExpect(jsonPath("$[0].end").value(END))
                .andExpect(jsonPath("$[1].status").value("Faulty"))
                .andExpect(jsonPath("$[1].start").value(END))
                .andExpect(jsonPath("$[1].end").isEmpty());
    }

    @Test
    @DisplayName("TS03 (boundary): viewing units for a period that does not move forward is refused")
    void unitsNeedAPeriodThatMovesForward() throws Exception {
        units("ts1", START, START)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("end must be after start"));
    }
}
