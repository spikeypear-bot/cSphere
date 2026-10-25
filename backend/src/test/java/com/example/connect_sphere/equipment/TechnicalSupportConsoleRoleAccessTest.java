package com.example.connect_sphere.equipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS09 / AU04 (decision-log D20, D32): the Technical Support console's new
 * and extended reads are Technical Support's only, refused at the filter
 * chain before any lookup, so even a made-up id gets 403 rather than 404. A
 * kind's reservations name events, so coordinators must not reach them
 * (ECL-C7). The reads that already belonged to other roles — the plain
 * catalogue (EO, EC) and TS03's unit list (EC) — must stay open to them.
 */
@Tag("integration")
@Tag("TS09")
@Tag("AU04")
@Tag("TS01")
@Tag("TS03")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TechnicalSupportConsoleRoleAccessTest {

    private static final String ID = UUID.randomUUID().toString();
    private static final String WINDOW = "from=2027-03-10T01:00:00Z&to=2027-03-10T04:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    static Stream<Arguments> refused() {
        String[] technicalSupportOnly = {
            "/api/equipment-requests",
            "/api/equipment-requests?status=all",
            "/api/equipment/status-periods?current=true",
            "/api/equipment/" + ID,
            "/api/equipment/" + ID + "/reservations?" + WINDOW,
            "/api/equipment/catalogue?" + WINDOW,
            "/api/equipment/catalogue?from=2027-03-10T01:00:00Z",
            "/api/equipment-damage-reports?equipmentId=" + ID};
        return Stream.of("ec1", "ecl1", "eo1", "vs1", "so1", "att1")
                .flatMap(user -> Stream.of(technicalSupportOnly).map(path -> Arguments.of(user, path)));
    }

    @ParameterizedTest(name = "{0} GET {1} -> 403")
    @MethodSource("refused")
    @DisplayName("TS09 (negative): every role but Technical Support is refused the console's reads")
    void otherRolesAreRefused(String user, String path) throws Exception {
        mvc.perform(get(path).with(flow.as(user))).andExpect(status().isForbidden());
    }

    static Stream<Arguments> stillOpen() {
        return Stream.of(
                Arguments.of("eo1", "/api/equipment/catalogue"),
                Arguments.of("ec1", "/api/equipment/catalogue"),
                Arguments.of("ts1", "/api/equipment/catalogue"),
                Arguments.of("ec1", "/api/equipment/units?start=2027-03-10T01:00:00Z&end=2027-03-10T04:00:00Z"),
                Arguments.of("ts1", "/api/equipment/catalogue?" + WINDOW),
                Arguments.of("ts1", "/api/equipment-requests?status=all"),
                Arguments.of("ts1", "/api/equipment/status-periods"));
    }

    @ParameterizedTest(name = "{0} GET {1} -> 200")
    @MethodSource("stillOpen")
    @DisplayName("TS09 (cross-cutting): the plain catalogue and TS03's unit list stay open to the roles that had them, and Technical Support reach the console's reads")
    void existingReadersKeepAccess(String user, String path) throws Exception {
        mvc.perform(get(path).with(flow.as(user))).andExpect(status().isOk());
    }
}
