package com.example.connect_sphere.eventrequest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.connect_sphere.common.web.RestAccessDeniedHandler;
import com.example.connect_sphere.common.web.RestAuthenticationEntryPoint;
import com.example.connect_sphere.config.JwtConfig;
import com.example.connect_sphere.config.SecurityConfig;
import com.example.connect_sphere.eventrequest.dto.CoordinatorDto;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * ECL-C3 and ECL-C6: only the Event Coordinator Lead may open a request's
 * review, act on it there or assign it, and the Lead's token opens none of
 * the coordinator's actions. Controller slice with the real SecurityConfig
 * imported; no database needed.
 */
@Tag("unit")
@WebMvcTest(EventRequestController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
@Tag("ECL-C3")
@Tag("ECL-C6")
@Tag("AU04")
class LeadRequestReviewAccessTest {

    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final String REVIEW = "/api/event-requests/unassigned/" + REQUEST;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private EventRequestService service;

    /** A token with the given role claim, run through the real converter. */
    private JwtRequestPostProcessor tokenFor(String roleClaim) {
        return jwt().jwt(token -> token.subject(LEAD.toString())
                        .claim("role", roleClaim)
                        .claim("organisation", "ConnectSphere"))
                .authorities(token -> jwtAuthenticationConverter.convert(token).getAuthorities());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The Lead's calls, by name so a failure says which one. */
    private static MockHttpServletRequestBuilder leadCall(String name) {
        return switch (name) {
            case "review" -> get(REVIEW);
            case "reject" -> json(post(REVIEW + "/reject"), "{\"reason\":\"Not a corporate event\"}");
            case "clarify" -> json(post(REVIEW + "/clarifications"), "{\"message\":\"Who is it for?\"}");
            case "coordinators" -> get("/api/event-requests/unassigned/coordinators");
            case "assign" -> json(post(REVIEW + "/assign"), "{\"coordinatorUserId\":\"" + COORDINATOR + "\"}");
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    @DisplayName("ECL-C3 (happy): the Lead can open a request's review")
    void aLeadCanOpenARequestsReview() throws Exception {
        mockMvc.perform(leadCall("review").with(tokenFor("ecl")))
                .andExpect(status().isOk());

        verify(service).getForLeadReview(REQUEST);
    }

    @Test
    @DisplayName("ECL-C3 (happy): the Lead's rejection is recorded against the signed-in Lead")
    void aLeadsRejectionIsRecordedAgainstTheSignedInLead() throws Exception {
        mockMvc.perform(leadCall("reject").with(tokenFor("ecl")))
                .andExpect(status().isOk());

        verify(service).rejectUnassigned(LEAD, REQUEST, "Not a corporate event");
    }

    @Test
    @DisplayName("ECL-C3 (happy): the Lead's clarification request is recorded against the signed-in Lead")
    void aLeadsClarificationIsRecordedAgainstTheSignedInLead() throws Exception {
        mockMvc.perform(leadCall("clarify").with(tokenFor("ecl")))
                .andExpect(status().isOk());

        verify(service).requestClarificationUnassigned(LEAD, REQUEST, "Who is it for?");
    }

    @Test
    @DisplayName("ECL-C6 (happy): the Lead can list the Event Coordinators to assign a request to")
    void aLeadCanListTheEventCoordinatorsToAssignTo() throws Exception {
        when(service.coordinators()).thenReturn(List.of(new CoordinatorDto(COORDINATOR, "ec1")));

        mockMvc.perform(leadCall("coordinators").with(tokenFor("ecl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(COORDINATOR.toString()))
                .andExpect(jsonPath("$[0].username").value("ec1"));

        // Not swallowed by GET /unassigned/{id}.
        verify(service, never()).getForLeadReview(any());
    }

    @Test
    @DisplayName("ECL-C6 (happy): the Lead's assignment is recorded against the signed-in Lead")
    void aLeadsAssignmentIsRecordedAgainstTheSignedInLead() throws Exception {
        mockMvc.perform(leadCall("assign").with(tokenFor("ecl")))
                .andExpect(status().isOk());

        verify(service).assignCoordinator(LEAD, REQUEST, COORDINATOR);
    }

    static Stream<Arguments> everyOtherRoleAndCall() {
        return Stream.of("ec", "eo", "vs", "technician", "attendee")
                .flatMap(role -> Stream.of("review", "reject", "clarify", "coordinators", "assign")
                        .map(call -> Arguments.of(role, call)));
    }

    @ParameterizedTest
    @MethodSource("everyOtherRoleAndCall")
    @DisplayName("ECL-C3 ECL-C6 AU04 (negative): every other role is refused the Lead's review, rejection, clarification, coordinator list and assignment, and no request data is read or changed")
    void everyOtherRoleIsDeniedAndNoRequestDataIsReadOrChanged(String otherRole, String call) throws Exception {
        mockMvc.perform(leadCall(call).with(tokenFor(otherRole)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.request").doesNotExist());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = { "review", "reject", "clarify", "coordinators", "assign" })
    @DisplayName("ECL-C3 ECL-C6 AU04 (negative): a call without signing in is rejected and no request data is read or changed")
    void aCallWithNoTokenIsRejectedAndNoRequestDataIsReadOrChanged(String call) throws Exception {
        mockMvc.perform(leadCall(call))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    /** Approval stays with the assigned coordinator, as do the coordinator's
     * own review, rejection and clarification. */
    @ParameterizedTest
    @ValueSource(strings = { "approve", "reject", "clarifications" })
    @DisplayName("ECL-C3 AU04 (negative): the Lead cannot approve, reject or ask for clarification through the coordinator's actions")
    void aLeadTokenDoesNotOpenTheCoordinatorsActions(String action) throws Exception {
        mockMvc.perform(json(post("/api/event-requests/" + REQUEST + "/" + action), "{}").with(tokenFor("ecl")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    /** The self-assignment call an Event Coordinator used to make (ECL-C6). */
    private static MockHttpServletRequestBuilder removedSelfAssignment() {
        return json(post("/api/event-requests/" + REQUEST + "/assign-coordinator"),
                "{\"coordinatorUserId\":\"" + COORDINATOR + "\"}");
    }

    /** ECL-C6: coordinators no longer assign, to themselves or anyone else. */
    @ParameterizedTest
    @ValueSource(strings = { "ec", "ecl", "vs", "technician", "attendee" })
    @DisplayName("ECL-C6 (negative): the removed self-assignment call is refused for every role and assigns nothing")
    void theRemovedSelfAssignmentCallIsDeniedAndAssignsNothing(String role) throws Exception {
        mockMvc.perform(removedSelfAssignment().with(tokenFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(service);
    }

    /** It falls under the organisers' blanket rule now, where no such call exists. */
    @Test
    @DisplayName("ECL-C6 (negative): the removed self-assignment call is refused for an organiser too and assigns nothing")
    void theRemovedSelfAssignmentCallDoesNotExistForAnOrganiserEither() throws Exception {
        mockMvc.perform(removedSelfAssignment().with(tokenFor("eo")))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("ECL-C3 AU04 (negative): the Lead cannot open the coordinator's review screen")
    void aLeadTokenDoesNotOpenTheCoordinatorsReviewScreen() throws Exception {
        mockMvc.perform(get("/api/event-requests/" + REQUEST + "/review").with(tokenFor("ecl")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
