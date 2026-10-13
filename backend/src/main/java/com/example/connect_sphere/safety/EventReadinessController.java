package com.example.connect_sphere.safety;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/readiness")
public class EventReadinessController {

    private final ReadinessService readiness;

    public EventReadinessController(ReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    public ReadinessDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        // The token's role claim is the UserRole name (TokenService).
        if ("safety_officer".equalsIgnoreCase(jwt.getClaimAsString("role"))) {
            return readiness.forSafetyOfficer(eventId);
        }
        return readiness.forCoordinator(UUID.fromString(jwt.getSubject()), eventId);
    }
}
