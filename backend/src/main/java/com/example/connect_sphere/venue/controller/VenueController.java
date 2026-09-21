package com.example.connect_sphere.venue.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.UpdateVenueDto;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.service.VenueService;

/** Catalogue API. Verified server-side identity/role enforcement is pending D6a/Q2. */
@RestController
@RequestMapping("/api/venues")
public class VenueController {
    private final VenueService service;

    public VenueController(VenueService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VenueDto> create(@RequestBody CreateVenueDto input) {
        VenueDto saved = service.createVenue(input);
        return ResponseEntity.created(URI.create("/api/venues/" + saved.venueId())).body(saved);
    }

    @PutMapping("/{venueId}")
    public VenueDto update(@PathVariable("venueId") UUID venueId, @RequestBody UpdateVenueDto input) {
        return service.updateVenue(venueId, input);
    }

    @GetMapping
    public List<VenueDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public VenueDto get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidBody(HttpMessageNotReadableException ex) {
        // Use only known field names, never expose parser internals or submitted values.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jsonError) {
                for (var reference : jsonError.getPath()) {
                    String field = reference.getPropertyName();
                    if (field == null) continue;
                    String message = switch (field) {
                        case "venueCapacity" -> "venueCapacity must be a whole number between 1 and 50000; null is not allowed";
                        case "supportedLayouts" -> "supportedLayouts must be an array of valid layout selections; null is not allowed";
                        case "venueAccessibilities" -> "venueAccessibilities must be an array of valid accessibility selections; use [] to clear selections, not null";
                        case "venueFacilities" -> "venueFacilities must be an array of valid facility selections; use [] to clear selections, not null";
                        case "additionalInformation" -> "additionalInformation must be text; use an empty string to clear it, not null";
                        case "operatingInformation" -> "operatingInformation must be nonblank text; null is not allowed";
                        default -> null;
                    };
                    if (message != null) return ResponseEntity.badRequest().body(ApiError.of(message));
                }
            }
        }
        return ResponseEntity.badRequest().body(ApiError.of(
                "Invalid venue JSON: check field types, supported layouts, accessibility and facility values."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidId(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("Venue ID must be a valid UUID."));
    }
}
