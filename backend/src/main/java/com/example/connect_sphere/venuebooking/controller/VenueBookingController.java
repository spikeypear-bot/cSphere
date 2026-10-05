package com.example.connect_sphere.venuebooking.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.venuebooking.dto.VenueBookingDto;
import com.example.connect_sphere.venuebooking.service.VenueBookingService;
import com.example.connect_sphere.venuebooking.service.VenueBookingNotFoundException;

/** Booking reads and VS03 approval. Role access is enforced by SecurityConfig; ownership is deferred. */
@RestController
@RequestMapping("/api")
public class VenueBookingController {
    private final VenueBookingService service;

    public VenueBookingController(VenueBookingService service) { this.service = service; }

    @PatchMapping("/venue-bookings/{bookingId}/approve")
    public VenueBookingDto approve(@PathVariable UUID bookingId) { return service.approve(bookingId); }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ApiError> updateFailed(RuntimeException ex) {
        return ResponseEntity.status(503).body(ApiError.of("The booking could not be updated. Refresh its details and try again."));
    }

    @GetMapping("/venue-staff/booking-requests")
    public List<VenueBookingDto> pending() { return service.listPending(); }

    @GetMapping("/venue-bookings/{bookingId}")
    public VenueBookingDto get(@PathVariable UUID bookingId) { return service.get(bookingId); }

    @GetMapping("/venues/{venueId}/bookings")
    public List<VenueBookingDto> list(@PathVariable UUID venueId) { return service.listForVenue(venueId); }

    @ExceptionHandler(VenueBookingNotFoundException.class)
    public ResponseEntity<ApiError> notFound(VenueBookingNotFoundException ex) {
        return ResponseEntity.status(404).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidId(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("Venue and booking IDs must be valid UUIDs."));
    }
}
