package com.example.connect_sphere.venue.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
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
        return ResponseEntity.badRequest().body(ApiError.of(
                "Invalid venue JSON: check field types and supported layout values."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidId(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("Venue ID must be a valid UUID."));
    }
}
