package com.example.connect_sphere.changerequest;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.venuebooking.service.*;

/** VS17: explicit SecurityConfig role rules, plus assignment scope for coordinator reads. */
@RestController
@RequestMapping("/api")
public class VenueRequirementReviewController {
    private final VenueRequirementReviewService service;
    public VenueRequirementReviewController(VenueRequirementReviewService service) { this.service=service; }
    @GetMapping("/venue-bookings/{bookingId}/requirement-reviews")
    public List<VenueRequirementReviewService.View> booking(@PathVariable UUID bookingId) { return service.forBooking(bookingId); }
    @GetMapping("/events/{eventId}/venue-reviews")
    public List<VenueRequirementReviewService.View> event(@PathVariable UUID eventId, @AuthenticationPrincipal Jwt jwt) {
        return service.forCoordinator(eventId,UUID.fromString(jwt.getSubject()));
    }
    @PostMapping("/venue-bookings/{bookingId}/requirement-reviews/{reviewId}/decision")
    public VenueRequirementReviewService.View decide(@PathVariable UUID bookingId, @PathVariable UUID reviewId,
            @AuthenticationPrincipal Jwt jwt, @RequestBody VenueRequirementReviewService.Decision decision) {
        return service.decide(bookingId,reviewId,UUID.fromString(jwt.getSubject()),decision);
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ApiError> failure(RuntimeException ex) {
        return ResponseEntity.status(503).body(ApiError.of("The requirement review could not be saved or loaded. Refresh and try again."));
    }
}
