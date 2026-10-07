package com.example.connect_sphere.event.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.service.EventService;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * EC09: only an Event Coordinator may open "My events", and the list is
 * always the caller's own. Controller slice with the real SecurityConfig
 * imported; no database needed.
 */
@Tag("unit")
@WebMvcTest(EventController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class AssignedEventsAccessTest {

    private static final String ASSIGNED = "/api/events/assigned";
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private EventService service;

    @MockitoBean
    private EventRequestService eventRequestService;

    /** A token for {@link #CALLER_ID} with the given role claim, run through the real converter. */
    private JwtRequestPostProcessor tokenFor(String roleClaim) {
        return jwt().jwt(token -> token.subject(CALLER_ID.toString())
                        .claim("role", roleClaim)
                        .claim("organisation", "ConnectSphere"))
                .authorities(token -> jwtAuthenticationConverter.convert(token).getAuthorities());
    }

    @Test
    void anEventCoordinatorGetsTheirOwnEventsWithWhatTheListShows() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(service.assignedEvents(CALLER_ID)).thenReturn(List.of(new EventDto(
                eventId, "Q1 Town Hall", "All-hands update", null,
                OffsetDateTime.parse("2027-03-10T01:00:00Z"), OffsetDateTime.parse("2027-03-10T04:00:00Z"),
                150, null, List.of(), false, "Acme Pte Ltd", "Theatre seating", null,
                "confirmed", "ec1", "ec1@connectsphere.test")));

        mockMvc.perform(get(ASSIGNED).with(tokenFor("ec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].eventName").value("Q1 Town Hall"))
                .andExpect(jsonPath("$[0].organisation").value("Acme Pte Ltd"))
                .andExpect(jsonPath("$[0].startDatetime").value("2027-03-10T01:00:00Z"))
                .andExpect(jsonPath("$[0].endDatetime").value("2027-03-10T04:00:00Z"))
                .andExpect(jsonPath("$[0].expectedAttendance").value(150))
                .andExpect(jsonPath("$[0].status").value("confirmed"));
    }

    @Test
    void aCoordinatorWithNoAssignedEventsGetsAnEmptyListNotAnError() throws Exception {
        when(service.assignedEvents(CALLER_ID)).thenReturn(List.of());

        mockMvc.perform(get(ASSIGNED).with(tokenFor("ec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** The list is the token's subject's; nothing in the request can name another coordinator. */
    @ParameterizedTest
    @ValueSource(strings = { "coordinatorId", "coordinatorUserId", "userId" })
    void aParameterNamingAnotherCoordinatorIsIgnored(String parameter) throws Exception {
        when(service.assignedEvents(CALLER_ID)).thenReturn(List.of());

        mockMvc.perform(get(ASSIGNED).param(parameter, OTHER_COORDINATOR_ID.toString()).with(tokenFor("ec")))
                .andExpect(status().isOk());

        verify(service).assignedEvents(CALLER_ID);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ecl", "eo", "vs", "technician", "attendee" })
    void everyOtherRoleIsDeniedAndNoEventDataIsRead(String otherRole) throws Exception {
        mockMvc.perform(get(ASSIGNED).with(tokenFor(otherRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$[0]").doesNotExist());

        verifyNoInteractions(service, eventRequestService);
    }

    @Test
    void aCallWithNoTokenIsRejectedAndNoEventDataIsRead() throws Exception {
        mockMvc.perform(get(ASSIGNED))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service, eventRequestService);
    }
}
