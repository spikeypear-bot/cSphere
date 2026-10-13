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
import com.example.connect_sphere.safety.ReadinessService;
import com.example.connect_sphere.safety.SafetyApprovalRequest;
import com.example.connect_sphere.safety.ReadinessDto;
import com.example.connect_sphere.user.entity.UserRole;

/**
 * Event and Coordinator read endpoints. Safety Officer event access and the
 * checklist-gated confirmation flow live in the safety package.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;
    private final EventRequestService eventRequestService;
    private final ReadinessService readinessService;

    public EventController(EventService service, EventRequestService eventRequestService,
            ReadinessService readinessService) {
        this.service = service;
        this.eventRequestService = eventRequestService;
        this.readinessService = readinessService;
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

    /** EC09: the caller's own active events. Coordinator only (see SecurityConfig). */
    @GetMapping("/assigned")
    public List<EventDto> assignedEvents(@AuthenticationPrincipal Jwt jwt) {
        return service.assignedEvents(userIdOf(jwt));
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
    public ReadinessDto confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return readinessService.review(userIdOf(jwt), id, new SafetyApprovalRequest("approve", null));
    }
}
