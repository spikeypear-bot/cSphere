package com.example.connect_sphere.event.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.event.dto.CancellationReasonRequest;
import com.example.connect_sphere.event.service.EventCancellationService;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;

/**
 * EO07 endpoints. Which role may reach each path is SecurityConfig's (D20);
 * the organisation (from the token's claim, never the request) and the
 * coordinator's assignment are checked in {@link EventCancellationService}.
 */
@RestController
public class EventCancellationController {

    private final EventCancellationService service;

    public EventCancellationController(EventCancellationService service) {
        this.service = service;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String organisationOf(Jwt jwt) {
        return jwt.getClaimAsString("organisation");
    }

    private static String reasonOf(CancellationReasonRequest body) {
        return body == null ? null : body.reason();
    }

    /** Before approval: the organiser cancels their request at once. */
    @PostMapping("/api/event-requests/{requestId}/cancel")
    public EventRequestDto cancelRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
            @RequestBody(required = false) CancellationReasonRequest body) {
        return service.cancelRequest(organisationOf(jwt), userIdOf(jwt), requestId, reasonOf(body));
    }
}
