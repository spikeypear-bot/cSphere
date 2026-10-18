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

import com.example.connect_sphere.event.dto.CreateEventReassignmentRequest;
import com.example.connect_sphere.event.dto.DecideEventReassignmentRequest;
import com.example.connect_sphere.event.dto.EventReassignmentRequestDto;
import com.example.connect_sphere.event.service.EventReassignmentService;
import com.example.connect_sphere.eventrequest.dto.CoordinatorDto;

@RestController
public class EventReassignmentController {

    private final EventReassignmentService service;

    public EventReassignmentController(EventReassignmentService service) {
        this.service = service;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @PostMapping("/api/events/{eventId}/reassignment-requests")
    public EventReassignmentRequestDto request(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @RequestBody CreateEventReassignmentRequest body) {
        return service.request(userIdOf(jwt), eventId, body.reason());
    }

    @GetMapping("/api/events/{eventId}/reassignment-requests")
    public List<EventReassignmentRequestDto> latestForCoordinator(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.latestForCoordinator(userIdOf(jwt), eventId);
    }

    @GetMapping("/api/event-reassignment-requests")
    public List<EventReassignmentRequestDto> pending() {
        return service.pendingRequests();
    }

    @GetMapping("/api/event-reassignment-requests/coordinators")
    public List<CoordinatorDto> coordinators() {
        return service.coordinators();
    }

    @PostMapping("/api/event-reassignment-requests/{requestId}/approve")
    public EventReassignmentRequestDto approve(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
            @RequestBody DecideEventReassignmentRequest body) {
        return service.approve(userIdOf(jwt), requestId, body.coordinatorUserId());
    }

    @PostMapping("/api/event-reassignment-requests/{requestId}/decline")
    public EventReassignmentRequestDto decline(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
            @RequestBody DecideEventReassignmentRequest body) {
        return service.decline(userIdOf(jwt), requestId, body.reason());
    }
}
