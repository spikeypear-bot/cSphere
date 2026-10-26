package com.example.connect_sphere.equipmentdamage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS09 AC17 / AU04: every TS09 endpoint refuses the roles with no business
 * there at the filter chain, before any lookup, so even a made-up id gets
 * 403 rather than 404. The roles that do belong are covered by
 * {@link EquipmentDamageFlowTest}.
 */
@Tag("integration")
@Tag("TS09")
@Tag("AU04")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentDamageRoleAccessTest {

    private static final String ID = UUID.randomUUID().toString();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    static Stream<Arguments> refused() {
        String[] reports = {"GET /api/equipment-damage-reports", "POST /api/equipment-damage-reports",
            "GET /api/equipment-damage-reports/" + ID, "POST /api/equipment-damage-reports/" + ID + "/outcomes/" + ID,
            "POST /api/equipment-damage-reports/" + ID + "/close"};
        String read = "GET /api/events/" + ID + "/equipment-damage";
        String resolve = "POST /api/events/" + ID + "/equipment-damage/" + ID + "/resolve";
        Stream<Arguments> technicalSupportOnly = Stream.of("ec1", "ecl1", "eo1", "vs1", "so1", "att1")
                .flatMap(user -> Stream.of(reports).map(endpoint -> Arguments.of(user, endpoint)));
        Stream<Arguments> coordinatorRead = Stream.of("ts1", "eo1", "vs1", "so1", "att1")
                .map(user -> Arguments.of(user, read));
        Stream<Arguments> coordinatorAct = Stream.of("ts1", "ecl1", "eo1", "vs1", "so1", "att1")
                .map(user -> Arguments.of(user, resolve));
        return Stream.of(technicalSupportOnly, coordinatorRead, coordinatorAct).flatMap(s -> s);
    }

    @ParameterizedTest(name = "{0} {1} -> 403")
    @MethodSource("refused")
    @DisplayName("TS09 AC17 (negative): roles without a part in TS09 are refused")
    void unrelatedRolesAreRefused(String user, String endpoint) throws Exception {
        String[] parts = endpoint.split(" ", 2);
        MockHttpServletRequestBuilder request = "GET".equals(parts[0]) ? get(parts[1])
                : post(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
        mvc.perform(request.with(flow.as(user))).andExpect(status().isForbidden());
    }
}
