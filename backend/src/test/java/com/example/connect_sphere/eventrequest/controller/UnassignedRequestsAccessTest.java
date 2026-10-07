package com.example.connect_sphere.eventrequest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.common.web.RestAccessDeniedHandler;
import com.example.connect_sphere.common.web.RestAuthenticationEntryPoint;
import com.example.connect_sphere.config.JwtConfig;
import com.example.connect_sphere.config.SecurityConfig;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * ECL-C1: only the Event Coordinator Lead may open the unassigned queue.
 * Controller slice with the real SecurityConfig imported; no database needed.
 */
@Tag("unit")
@WebMvcTest(EventRequestController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class UnassignedRequestsAccessTest {

    private static final String UNASSIGNED = "/api/event-requests/unassigned";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private EventRequestService service;

    /** A token with the given role claim, run through the real converter. */
    private JwtRequestPostProcessor tokenFor(String roleClaim) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString())
                        .claim("role", roleClaim)
                        .claim("organisation", "ConnectSphere"))
                .authorities(token -> jwtAuthenticationConverter.convert(token).getAuthorities());
    }

    @Test
    void anEventCoordinatorLeadCanOpenTheUnassignedQueue() throws Exception {
        when(service.unassignedRequests()).thenReturn(List.of());

        mockMvc.perform(get(UNASSIGNED).with(tokenFor("ecl")))
                .andExpect(status().isOk());

        verify(service).unassignedRequests();
    }

    @ParameterizedTest
    @ValueSource(strings = { "ec", "eo", "vs", "technician", "attendee" })
    void everyOtherRoleIsDeniedAndNoRequestDataIsRead(String otherRole) throws Exception {
        mockMvc.perform(get(UNASSIGNED).with(tokenFor(otherRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$[0]").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    void aCallWithNoTokenIsRejectedAndNoRequestDataIsRead() throws Exception {
        mockMvc.perform(get(UNASSIGNED))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    /** The Lead is a separate role: it does not open EC or EO endpoints. */
    @ParameterizedTest
    @ValueSource(strings = { "/api/event-requests/queue", "/api/event-requests" })
    void aLeadTokenDoesNotOpenCoordinatorOrOrganiserEndpoints(String path) throws Exception {
        mockMvc.perform(get(path).with(tokenFor("ecl")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
