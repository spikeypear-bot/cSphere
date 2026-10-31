package com.example.connect_sphere.venuemap;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import static com.example.connect_sphere.venuemap.VenueMapDto.*;

/** VS-only read models; all booking decisions remain in their existing workflows. */
@RestController
@RequestMapping("/api/venue-staff/map")
public class VenueMapController {
    private final VenueMapService service;
    public VenueMapController(VenueMapService service) { this.service=service; }

    @GetMapping("/activity")
    public List<Activity> activity(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.activity(date);
    }
    @GetMapping("/venues/{venueId}/bookings")
    public List<Booking> bookings(@PathVariable UUID venueId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="false") boolean includePast) {
        return service.bookings(venueId,date,includePast);
    }
    @ExceptionHandler(VenueNotFoundException.class)
    public ResponseEntity<ApiError> missing(VenueNotFoundException e) {
        return ResponseEntity.status(404).body(ApiError.of(e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiError.of("Use a valid venue ID, date (YYYY-MM-DD) and past-bookings selection."));
    }
}
