package com.example.connect_sphere.venuebooking.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.venuebooking.dto.EventVenueBookingDto;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.dto.VenueOptionDto;
import com.example.connect_sphere.venuebooking.service.VenueBookingRequestService;

/**
 * EC03, from the coordinator's side of one event. SecurityConfig limits these
 * paths to Event Coordinators; the service limits them further to the
 * coordinator assigned to the event, using the token's {@code sub}.
 */
@RestController
@RequestMapping("/api/events/{eventId}")
public class EventVenueBookingController {

    private final VenueBookingRequestService service;

    public EventVenueBookingController(VenueBookingRequestService service) {
        this.service = service;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Every venue, judged and ranked for this event. */
    @GetMapping("/venue-options")
    public List<VenueOptionDto> venueOptions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal setupDurationMinutes,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal turnaroundDurationMinutes,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.time.OffsetDateTime startDatetime,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.time.OffsetDateTime endDatetime,
            @org.springframework.web.bind.annotation.RequestParam(required=false) Integer expectedAttendance,
            @org.springframework.web.bind.annotation.RequestParam(required=false) List<String> requiredFacilities,
            @org.springframework.web.bind.annotation.RequestParam(required=false) List<String> accessibilityNeeds,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String venueRequirements) {
        return service.venueOptions(userIdOf(jwt), eventId, setupDurationMinutes, turnaroundDurationMinutes, startDatetime, endDatetime,
                new com.example.connect_sphere.venuebooking.dto.BookingRequirements(expectedAttendance,requiredFacilities,accessibilityNeeds,venueRequirements));
    }

    @GetMapping("/venue-bookings")
    public List<EventVenueBookingDto> bookings(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        // The token's role claim is the UserRole name (TokenService).
        // Read-only views that need no assignment: the Safety Officer's
        // review and the Event Coordinator Lead's oversight (ECL-C7).
        String role = jwt.getClaimAsString("role");
        if ("safety_officer".equalsIgnoreCase(role) || "ecl".equalsIgnoreCase(role)) {
            return service.bookingsForSafetyOfficer(eventId);
        }
        return service.bookingsForEvent(userIdOf(jwt), eventId);
    }

    /** VS25: cancel a pending or approved booking. Body: {"reason": "..."} (optional). */
    @PostMapping("/venue-bookings/{bookingId}/cancel")
    public EventVenueBookingDto cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        return service.cancel(userIdOf(jwt), eventId, bookingId, body == null ? null : body.get("reason"));
    }

    @PostMapping("/venue-bookings")
    public ResponseEntity<EventVenueBookingDto> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId,
            @RequestBody SubmitVenueBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(userIdOf(jwt), eventId, request));
    }

    @PostMapping("/venue-bookings/{bookingId}/withdraw-requirement")
    public EventVenueBookingDto withdraw(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID eventId,
            @PathVariable UUID bookingId,@RequestBody java.util.Map<String,String> body) {
        return service.withdrawRequirement(userIdOf(jwt),eventId,bookingId,body.get("reason"));
    }
    @GetMapping("/venue-bookings/{bookingId}/history")
    public List<java.util.Map<String,Object>> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID eventId,@PathVariable UUID bookingId) {
        return service.history(userIdOf(jwt),eventId,bookingId);
    }

    @PostMapping("/venue-bookings/batch")
    public ResponseEntity<List<EventVenueBookingDto>> submitBatch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId,
            @RequestBody SubmitVenueBookingRequest.Batch request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submitBatch(userIdOf(jwt), eventId, request));
    }
}
