package com.example.connect_sphere.event.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.service.EventService;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.user.entity.UserRole;

/**
 * EO09's "Confirmed" transition and "view confirmed event arrangements".
 * SecurityConfig splits GET (either role, organisation-checked in the
 * service for an Organiser) from the Coordinator-only confirm action.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;
    private final EventRequestService eventRequestService;

    public EventController(EventService service, EventRequestService eventRequestService) {
        this.service = service;
        this.eventRequestService = eventRequestService;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String roleOf(Jwt jwt) {
        return jwt.getClaimAsString("role");
    }

    private static String organisationOf(Jwt jwt) {
        return jwt.getClaimAsString("organisation");
    }

    @GetMapping("/{id}")
    public EventDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        // Coordinators aren't scoped to an organisation (same reasoning as
        // EventRequestController's queue/approve/reject); an Organiser only
        // ever sees their own organisation's event, same as everywhere else
        // in this app.
        if ("ec".equalsIgnoreCase(roleOf(jwt))) {
            return service.getForCoordinator(id);
        }
        return service.get(organisationOf(jwt), id);
    }

    /** The event's full timeline, filtered to what the caller's role may see. */
    @GetMapping("/{id}/timeline")
    public List<ActivityDto> timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        UserRole role = "ec".equalsIgnoreCase(roleOf(jwt)) ? UserRole.ec : UserRole.eo;
        return eventRequestService.timelineForEvent(role, organisationOf(jwt), userIdOf(jwt), id);
    }

    @PostMapping("/{id}/confirm")
    public EventDto confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return service.confirm(userIdOf(jwt), id);
    }
}
