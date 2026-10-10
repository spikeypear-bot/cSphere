package com.example.connect_sphere.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
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
 * AU04/DEV05 negative matrix for every endpoint added by EC01/EC02/EO26/EC03:
 * roles with no business there (Technical Support, Attendee) are refused by
 * the filter chain before any lookup, so even a made-up id gets 403, not 404.
 * The roles that do belong are covered by the flow tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NewEndpointRoleAccessTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    private FlowSupport flow;

    private static final String ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    static Stream<Arguments> endpointsForUnrelatedRoles() {
        String[][] endpoints = {
            {"GET", "/api/event-requests/queue"},
            {"GET", "/api/event-requests/" + ID + "/review"},
            {"POST", "/api/event-requests/" + ID + "/clarifications"},
            {"GET", "/api/event-requests/" + ID + "/timeline"},
            {"POST", "/api/event-requests/" + ID + "/resubmit"},
            {"GET", "/api/events/" + ID + "/timeline"},
            {"GET", "/api/events/" + ID + "/venue-options"},
            {"GET", "/api/events/" + ID + "/venue-bookings"},
            {"POST", "/api/events/" + ID + "/venue-bookings"},
            {"POST", "/api/events/" + ID + "/venue-bookings/" + ID + "/cancel"},
        };
        return Stream.of("tech1", "att1").flatMap(user ->
                Stream.of(endpoints).map(e -> Arguments.of(user, e[0], e[1])));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> 403")
    @MethodSource("endpointsForUnrelatedRoles")
    void unrelatedRolesAreRefused(String user, String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = "GET".equals(method) ? get(path)
                : post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
        mvc.perform(request.with(flow.as(user))).andExpect(status().isForbidden());
    }
}
