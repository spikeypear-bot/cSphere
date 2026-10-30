package com.example.connect_sphere.event.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.dto.EventReadinessDto;
import com.example.connect_sphere.event.dto.OrganiserArrangementsDto;
import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.dto.RegistrationPeriodRequest;
import com.example.connect_sphere.event.service.EventArrangementsService;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.event.service.EventRegistrationService;
import com.example.connect_sphere.event.service.EventRegistrationPeriodService;
import com.example.connect_sphere.event.service.EventService;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.user.entity.UserRole;

/**
 * Event endpoints for Organisers and Coordinators, including the EC08
 * lifecycle (send for safety review, withdraw, confirm). The Safety Officer's
 * checklist and decision endpoints live in the safety package (SO-C1–C4).
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;
    private final EventRequestService eventRequestService;
    private final EventLifecycleService lifecycle;
    private final EventArrangementsService arrangements;
    private final EventRegistrationService registrationService;
    private final EventRegistrationPeriodService registrationPeriodService;

    public EventController(EventService service, EventRequestService eventRequestService,
            EventLifecycleService lifecycle, EventArrangementsService arrangements,
            EventRegistrationService registrationService, EventRegistrationPeriodService registrationPeriodService) {
        this.registrationService = registrationService;
        this.registrationPeriodService = registrationPeriodService;
        this.service = service;
        this.eventRequestService = eventRequestService;
        this.lifecycle = lifecycle;
        this.arrangements = arrangements;
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

    /** ECL-C7: every coordinator's active events. Lead only (see SecurityConfig). */
    @GetMapping("/by-coordinator")
    public List<com.example.connect_sphere.event.dto.CoordinatorEventsDto> eventsByCoordinator() {
        return service.activeEventsByCoordinator();
    }

    /** EC09: the caller's own active events. Coordinator only (see SecurityConfig). */
    @GetMapping("/assigned")
    public List<EventDto> assignedEvents(@AuthenticationPrincipal Jwt jwt) {
        return service.assignedEvents(userIdOf(jwt));
    }

    @GetMapping("/{id}")
    public EventDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        // ECL-C7: a coordinator sees only events assigned to them; the Lead
        // sees any event (oversight, read-only); an Organiser only their own
        // organisation's (D20).
        if ("ec".equalsIgnoreCase(roleOf(jwt))) {
            return service.getForCoordinator(userIdOf(jwt), id);
        }
        if ("ecl".equalsIgnoreCase(roleOf(jwt))) {
            return service.getForLead(id);
        }
        return service.get(organisationOf(jwt), id);
    }

    /** The event's full timeline, filtered to what the caller's role may see. */
    @GetMapping("/{id}/timeline")
    public List<ActivityDto> timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        String claim = roleOf(jwt);
        UserRole role = "ec".equalsIgnoreCase(claim) ? UserRole.ec
                : "ecl".equalsIgnoreCase(claim) ? UserRole.ecl : UserRole.eo;
        return eventRequestService.timelineForEvent(role, organisationOf(jwt), userIdOf(jwt), id);
    }

    /**
     * EC08: the coordinator's checklist (venues, equipment, safety check) and
     * which lifecycle actions are available now. Separate from
     * {@code /readiness}, which is the Safety Officer's SO-C3 checklist.
     */
    @GetMapping("/{id}/lifecycle")
    public EventReadinessDto readiness(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return lifecycle.readiness(userIdOf(jwt), id);
    }

    /** EC08: hand a ready event to the Safety Officer. */
    @PostMapping("/{id}/safety-review")
    public EventReadinessDto sendForSafetyReview(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return lifecycle.sendForSafetyReview(userIdOf(jwt), id);
    }

    /** EC08: take it back before the Safety Officer decides. Body: {"reason": "..."}. */
    @PostMapping("/{id}/safety-review/withdraw")
    public EventReadinessDto withdrawFromSafetyReview(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        return lifecycle.withdrawFromSafetyReview(userIdOf(jwt), id, body == null ? null : body.get("reason"));
    }

    /** EO04: the organiser's view of the confirmed arrangements. */
    @GetMapping("/{id}/arrangements")
    public OrganiserArrangementsDto arrangements(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return arrangements.forOrganiser(organisationOf(jwt), id);
    }

    /** EO10: the owning organiser's registration information. */
    @GetMapping("/{id}/registrations")
    public RegistrationInfoDto registrations(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return registrationService.forOrganiser(organisationOf(jwt), id);
    }

    /** EO12: update the registration period for the owning organisation's event. */
    @PutMapping("/{id}/registration-period")
    public RegistrationInfoDto updateRegistrationPeriod(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id, @RequestBody RegistrationPeriodRequest request) {
        return registrationPeriodService.update(organisationOf(jwt), userIdOf(jwt), id, request);
    }

    /** EC08: confirm once the Safety Officer has approved (SO-C4). Assigned coordinator only. */
    @PostMapping("/{id}/confirm")
    public EventDto confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return service.confirm(userIdOf(jwt), id);
    }
}
