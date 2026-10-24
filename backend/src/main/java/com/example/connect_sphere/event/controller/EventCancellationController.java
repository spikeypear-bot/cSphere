package com.example.connect_sphere.event.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.event.dto.CancellationReasonRequest;
import com.example.connect_sphere.event.dto.EventCancellationRequestDto;
import com.example.connect_sphere.event.service.EventCancellationService;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.user.entity.UserRole;

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

    /** The token's role claim as the domain role; null for an unknown one,
     * which the service refuses. */
    private static UserRole roleOf(Jwt jwt) {
        try {
            return UserRole.valueOf(jwt.getClaimAsString("role"));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
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

    /** After approval: the organiser asks for the event to be cancelled. */
    @PostMapping("/api/event-requests/{requestId}/cancellation-request")
    public EventCancellationRequestDto requestCancellation(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId, @RequestBody(required = false) CancellationReasonRequest body) {
        return service.requestCancellation(organisationOf(jwt), userIdOf(jwt), requestId, reasonOf(body));
    }

    /** The assigned coordinator approves: the event is cancelled. Body optional: {"reason": "note"}. */
    @PostMapping("/api/events/{eventId}/cancellation-requests/{cancellationRequestId}/approve")
    public EventCancellationRequestDto approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @PathVariable UUID cancellationRequestId, @RequestBody(required = false) CancellationReasonRequest body) {
        return service.approve(userIdOf(jwt), eventId, cancellationRequestId, reasonOf(body));
    }

    /** The assigned coordinator declines, with a reason: the event carries on. */
    @PostMapping("/api/events/{eventId}/cancellation-requests/{cancellationRequestId}/decline")
    public EventCancellationRequestDto decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @PathVariable UUID cancellationRequestId, @RequestBody(required = false) CancellationReasonRequest body) {
        return service.decline(userIdOf(jwt), eventId, cancellationRequestId, reasonOf(body));
    }

    /** The event's cancellation requests, newest first. */
    @GetMapping("/api/events/{eventId}/cancellation-requests")
    public List<EventCancellationRequestDto> forEvent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.forEvent(roleOf(jwt), organisationOf(jwt), userIdOf(jwt), eventId);
    }
}
