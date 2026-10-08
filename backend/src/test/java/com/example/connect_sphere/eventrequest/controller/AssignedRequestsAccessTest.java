package com.example.connect_sphere.eventrequest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto;
import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto.AssignedRequest;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * ECL-C2: only the Event Coordinator Lead may open the list of assigned
 * requests. Controller slice with the real SecurityConfig imported; no
 * database needed.
 */
@Tag("unit")
@WebMvcTest(EventRequestController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class AssignedRequestsAccessTest {

    private static final String ASSIGNED = "/api/event-requests/assigned";

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
    void anEventCoordinatorLeadGetsEveryCoordinatorWithTheirRequestsInTheServicesOrder() throws Exception {
        UUID busy = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OffsetDateTime starts = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
        EventRequestDto request = new EventRequestDto(requestId, 'C', null, "Annual Summit", null, null,
                starts, starts.plusHours(3), 150, null, null, List.of(), null,
                EventRequestStatus.clarification_required, starts.minusMonths(3), starts.minusMonths(2),
                "Globex Holdings", busy, null, "eo3");
        when(service.assignedRequests()).thenReturn(List.of(
                new CoordinatorAssignmentsDto(busy, "ec1",
                        List.of(new AssignedRequest(request, OffsetDateTime.parse("2026-12-11T10:15:00+08:00")))),
                new CoordinatorAssignmentsDto(free, "ec2", List.of())));

        mockMvc.perform(get(ASSIGNED).with(tokenFor("ecl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].coordinatorId").value(busy.toString()))
                .andExpect(jsonPath("$[0].coordinatorName").value("ec1"))
                .andExpect(jsonPath("$[0].requests.length()").value(1))
                .andExpect(jsonPath("$[0].requests[0].request.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].requests[0].request.eventName").value("Annual Summit"))
                .andExpect(jsonPath("$[0].requests[0].request.organisation").value("Globex Holdings"))
                .andExpect(jsonPath("$[0].requests[0].request.expectedAttendance").value(150))
                .andExpect(jsonPath("$[0].requests[0].request.status").value("clarification_required"))
                .andExpect(jsonPath("$[0].requests[0].request.startDatetime").isNotEmpty())
                .andExpect(jsonPath("$[0].requests[0].request.endDatetime").isNotEmpty())
                .andExpect(jsonPath("$[0].requests[0].submittedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].coordinatorName").value("ec2"))
                .andExpect(jsonPath("$[1].requests.length()").value(0));

        // The literal path is not swallowed by the organiser's GET /{id}.
        verify(service, never()).get(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "ec", "eo", "vs", "technician", "attendee" })
    void everyOtherRoleIsDeniedAndNoRequestDataIsRead(String otherRole) throws Exception {
        mockMvc.perform(get(ASSIGNED).with(tokenFor(otherRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$[0]").doesNotExist())
                .andExpect(jsonPath("$.requests").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    void aCallWithNoTokenIsRejectedAndNoRequestDataIsRead() throws Exception {
        mockMvc.perform(get(ASSIGNED))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }
}
