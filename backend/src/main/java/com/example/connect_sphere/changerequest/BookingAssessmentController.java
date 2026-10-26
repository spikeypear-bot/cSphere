package com.example.connect_sphere.changerequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
@RestController
@RequestMapping("/api")
public class BookingAssessmentController {
    private final BookingAssessmentService service;
    public BookingAssessmentController(BookingAssessmentService service) { this.service=service; }
    public record Reassessment(UUID assessmentId, String note) {}
    @GetMapping("/venue-bookings/{id}/assessment") @PreAuthorize("hasRole('VS')")
    public BookingAssessmentService.View view(@PathVariable UUID id) { return service.view(id); }
    @GetMapping("/events/{id}/venue-assessments") @PreAuthorize("hasRole('EC')")
    public List<BookingAssessmentService.View> event(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        return service.forCoordinator(id,UUID.fromString(jwt.getSubject()));
    }
    @PostMapping("/venue-bookings/{id}/reassess") @PreAuthorize("hasRole('VS')")
    public BookingAssessmentService.View restore(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@RequestBody Reassessment input) {
        return service.restore(id,UUID.fromString(jwt.getSubject()),input.assessmentId(),input.note());
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public org.springframework.http.ResponseEntity<com.example.connect_sphere.common.web.ApiError> failure(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(503).body(com.example.connect_sphere.common.web.ApiError.of("The assessment could not be saved or loaded. Please retry."));
    }
}
