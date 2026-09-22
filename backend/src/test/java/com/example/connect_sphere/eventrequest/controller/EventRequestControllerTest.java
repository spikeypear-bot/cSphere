package com.example.connect_sphere.eventrequest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * HTTP-level slice test: does the controller wire requests/responses/errors
 * correctly, independent of real business logic (which EventRequestServiceTest
 * already covers). The service is mocked.
 */
@WebMvcTest(EventRequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class EventRequestControllerTest {

    private static final String ACME = "Acme Conferences";
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventRequestService service;

    /**
     * The controller reads its scope off the authenticated principal, so a slice
     * request needs one. It is set straight onto the SecurityContextHolder
     * rather than with SecurityMockMvcRequestPostProcessors.jwt(): that
     * post-processor saves through a SecurityContextRepository and relies on the
     * filter chain to load it back, and this slice runs with addFilters = false
     * (see the class javadoc). The real Bearer path is covered by
     * EventRequestScopingTest, which loads the actual chain.
     */
    @BeforeEach
    void authenticateAsAnOrganiserOfAcme() {
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(USER_ID.toString())
                .claim("organisation", ACME).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_EO"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static EventRequestDto draft(UUID id, String organisation) {
        return new EventRequestDto(id, 'C', null, null, null, null, null, null, null, null, null,
                List.of(), null, EventRequestStatus.draft, OffsetDateTime.now(), OffsetDateTime.now(),
                organisation, null, null);
    }

    @Test
    void savingADraftWithAnEmptyBodyReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.saveNewDraft(eq(ACME), eq(USER_ID), any())).thenReturn(draft(id, ACME));

        mockMvc.perform(post("/api/event-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    void scopesByTheTokenClaimAndIgnoresAnyOrganisationTheClientSends() throws Exception {
        when(service.list(ACME)).thenReturn(List.of(draft(UUID.randomUUID(), ACME)));

        mockMvc.perform(get("/api/event-requests")
                        .header("X-Organisation", "Globex Holdings"))
                .andExpect(status().isOk());

        verify(service).list(ACME);
    }
}
