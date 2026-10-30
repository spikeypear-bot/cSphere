package com.example.connect_sphere.event.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
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
import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.dto.RegistrationPeriodRequest;
import com.example.connect_sphere.event.service.EventArrangementsService;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.event.service.EventRegistrationPeriodService;
import com.example.connect_sphere.event.service.EventRegistrationService;
import com.example.connect_sphere.event.service.EventService;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

@Tag("unit")
@Tag("EO12")
@WebMvcTest(EventController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class EventRegistrationPeriodAccessTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String REQUEST = """
            {"opensAt":"2030-01-02T00:00:00Z","closesAt":"2030-01-03T00:00:00Z"}
            """;

    @Autowired MockMvc mvc;
    @Autowired JwtAuthenticationConverter converter;
    @MockitoBean EventService service;
    @MockitoBean EventRequestService eventRequestService;
    @MockitoBean EventLifecycleService lifecycle;
    @MockitoBean EventArrangementsService arrangements;
    @MockitoBean EventRegistrationService registrationService;
    @MockitoBean EventRegistrationPeriodService periodService;

    @Test
    @DisplayName("EO12 AC1 (negative): only an organiser role may update a registration period through the API")
    void onlyOrganiserCanUpdatePeriod() throws Exception {
        RegistrationInfoDto result = new RegistrationInfoDto(EVENT_ID, "Fair", true, "not_yet_open", 5, 0, 5,
                null, null, null, List.of());
        when(periodService.update("Acme", USER_ID, EVENT_ID,
                new RegistrationPeriodRequest("2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z")))
                .thenReturn(result);

        mvc.perform(put("/api/events/" + EVENT_ID + "/registration-period")
                        .with(tokenFor("eo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk());
        verify(periodService).update("Acme", USER_ID, EVENT_ID,
                new RegistrationPeriodRequest("2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z"));

        mvc.perform(put("/api/events/" + EVENT_ID + "/registration-period")
                        .with(tokenFor("ec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/events/" + EVENT_ID + "/registration-period")
                        .with(tokenFor("ecl"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, eventRequestService, lifecycle, arrangements, registrationService);
    }

    private JwtRequestPostProcessor tokenFor(String roleClaim) {
        return jwt().jwt(token -> token.subject(USER_ID.toString())
                        .claim("role", roleClaim)
                        .claim("organisation", "Acme"))
                .authorities(token -> converter.convert(token).getAuthorities());
    }
}
