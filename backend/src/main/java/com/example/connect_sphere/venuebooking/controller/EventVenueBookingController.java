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
    public List<VenueOptionDto> venueOptions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.venueOptions(userIdOf(jwt), eventId);
    }

    @GetMapping("/venue-bookings")
    public List<EventVenueBookingDto> bookings(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.bookingsForEvent(userIdOf(jwt), eventId);
    }

    /** EC03: withdraw a pending request. Body: {"reason": "..."} (optional). */
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
}
