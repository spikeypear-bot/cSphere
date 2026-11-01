package com.example.connect_sphere.equipmentreview;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Detail;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.OutcomeRequest;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Summary;

/**
 * TS11. {@code /api/equipment-change-reviews/**} is Technical Support's
 * (SecurityConfig); an event's reviews are the assigned coordinator's and the
 * Lead's, scoped by assignment in the service.
 */
@RestController
@RequestMapping("/api")
public class EquipmentChangeReviewController {

    private final EquipmentChangeReviewService service;

    public EquipmentChangeReviewController(EquipmentChangeReviewService service) {
        this.service = service;
    }

    @GetMapping("/equipment-change-reviews")
    public List<Summary> queue() {
        return service.queue();
    }

    @GetMapping("/equipment-change-reviews/{reviewId}")
    public Detail detail(@PathVariable UUID reviewId) {
        return service.detail(reviewId);
    }

    @PostMapping("/equipment-change-reviews/{reviewId}/outcome")
    public Detail recordOutcome(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reviewId,
            @RequestBody(required = false) OutcomeRequest request) {
        return service.recordOutcome(UUID.fromString(jwt.getSubject()), reviewId, request);
    }

    @GetMapping("/events/{eventId}/equipment-change-reviews")
    public List<Summary> forEvent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        // The token's role claim is the UserRole name (TokenService).
        boolean lead = "ecl".equalsIgnoreCase(jwt.getClaimAsString("role"));
        return service.forEvent(UUID.fromString(jwt.getSubject()), lead, eventId);
    }
}
