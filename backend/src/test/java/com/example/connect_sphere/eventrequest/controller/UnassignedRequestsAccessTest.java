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
 * ECL-C1: "only a signed-in Event Coordinator Lead can open the list of
 * unassigned event requests; any other role who tries gets an access-denied
 * message and sees no request data."
 *
 * <p>A controller slice with the <b>real</b> filter chain imported, and the
 * service mocked, so this needs no database. A plain {@code @WebMvcTest} loads
 * Spring Boot's default chain rather than {@link SecurityConfig} (which is why
 * {@code EventRequestControllerTest} switches filters off); importing the
 * configuration here is what makes the role rules under test the ones the
 * running application uses.
 *
 * <p>Authorities come from {@link JwtConfig}'s own converter applied to the
 * token's {@code role} claim, not from a hand-written {@code ROLE_ECL}: the
 * claim is lower-case ({@code ecl}) and the rule is an exact string match on
 * {@code ROLE_ECL}, so a test that built the authority itself would stay
 * green while real tokens were refused.
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

    /** A signed-in account as TokenService would mint it, for one role claim. */
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

        // Rejected by the filter chain before the controller: the queue was
        // never read, so there was nothing to leak.
        verifyNoInteractions(service);
    }

    @Test
    void aCallWithNoTokenIsRejectedAndNoRequestDataIsRead() throws Exception {
        mockMvc.perform(get(UNASSIGNED))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    /**
     * The Lead is a separate role, not an Event Coordinator with extra rights:
     * ROLE_ECL must not satisfy the coordinator's own queue, nor the Event
     * Organiser endpoints the blanket rule covers.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/event-requests/queue", "/api/event-requests" })
    void aLeadTokenDoesNotOpenCoordinatorOrOrganiserEndpoints(String path) throws Exception {
        mockMvc.perform(get(path).with(tokenFor("ecl")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
