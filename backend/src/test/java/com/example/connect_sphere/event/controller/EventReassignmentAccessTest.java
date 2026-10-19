package com.example.connect_sphere.event.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.common.web.RestAccessDeniedHandler;
import com.example.connect_sphere.common.web.RestAuthenticationEntryPoint;
import com.example.connect_sphere.config.JwtConfig;
import com.example.connect_sphere.config.SecurityConfig;
import com.example.connect_sphere.event.service.EventReassignmentService;

@Tag("unit")
@Tag("EC-C1")
@WebMvcTest(EventReassignmentController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class EventReassignmentAccessTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtAuthenticationConverter converter;
    @MockitoBean EventReassignmentService service;

    @Test
    void onlyTheAssignedCoordinatorRoleCanSubmitARequest() throws Exception {
        mvc.perform(post("/api/events/" + EVENT_ID + "/reassignment-requests").with(tokenFor("ec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I am no longer available.\"}"))
                .andExpect(status().isOk());
        verify(service).request(USER_ID, EVENT_ID, "I am no longer available.");

        mvc.perform(post("/api/events/" + EVENT_ID + "/reassignment-requests").with(tokenFor("ecl"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I am no longer available.\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePendingQueueIsLeadOnly() throws Exception {
        when(service.pendingRequests()).thenReturn(List.of());

        mvc.perform(get("/api/event-reassignment-requests").with(tokenFor("ecl")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/event-reassignment-requests").with(tokenFor("ec")))
                .andExpect(status().isForbidden());

        verify(service).pendingRequests();
    }

    @Test
    void unauthenticatedCallsAreRejected() throws Exception {
        mvc.perform(get("/api/event-reassignment-requests"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private JwtRequestPostProcessor tokenFor(String roleClaim) {
        return jwt().jwt(token -> token.subject(USER_ID.toString())
                        .claim("role", roleClaim)
                        .claim("organisation", "ConnectSphere"))
                .authorities(token -> converter.convert(token).getAuthorities());
    }
}
