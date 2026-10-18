package com.example.connect_sphere.venuebooking.hold;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.example.connect_sphere.venuebooking.service.VenueBookingService;
import com.example.connect_sphere.venuebooking.dto.VenueBookingDto;

@RestController
@RequestMapping("/api")
public class VenueHoldController {
    private final VenueHoldService service;
    private final VenueHoldRepository holds;
    private final VenueBookingService bookings;
    public VenueHoldController(VenueHoldService service,VenueHoldRepository holds,VenueBookingService bookings) {
        this.service=service;this.holds=holds;this.bookings=bookings;
    }
    @ExceptionHandler(com.example.connect_sphere.venuebooking.service.VenueBookingConflictException.class)
    public org.springframework.http.ResponseEntity<com.example.connect_sphere.venuebooking.dto.VenueBookingConflictResponse> conflict(
            com.example.connect_sphere.venuebooking.service.VenueBookingConflictException ex) {
        return org.springframework.http.ResponseEntity.status(409).body(new com.example.connect_sphere.venuebooking.dto.VenueBookingConflictResponse(
            ex.getMessage(),ex.getConflicts(),ex.getUnavailablePeriods()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public org.springframework.http.ResponseEntity<com.example.connect_sphere.common.web.ApiError> failed(RuntimeException ex) {
        return org.springframework.http.ResponseEntity.status(503).body(com.example.connect_sphere.common.web.ApiError.of(
            "The hold could not be updated. Refresh its details and try again."));
    }
    public record CreateHold(OffsetDateTime expiresAt) {}
    @GetMapping("/venue-bookings/{bookingId}/holds")
    public List<VenueHold> history(@PathVariable UUID bookingId) {return service.history(bookingId);}
    @PostMapping("/venue-bookings/{bookingId}/holds")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public VenueHold create(@PathVariable UUID bookingId,@RequestBody CreateHold body,@AuthenticationPrincipal Jwt jwt) {
        return service.create(bookingId,body.expiresAt(),UUID.fromString(jwt.getSubject()));
    }
    @PatchMapping("/venue-holds/{holdId}/release")
    public VenueHold release(@PathVariable UUID holdId,@AuthenticationPrincipal Jwt jwt) {
        return service.release(holdId,UUID.fromString(jwt.getSubject()));
    }
    @PatchMapping("/venue-holds/{holdId}/convert")
    public VenueBookingDto convert(@PathVariable UUID holdId) {return bookings.approve(holds.bookingId(holdId),holdId);}
}
