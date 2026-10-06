package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;

@RestController
@RequestMapping("/api/venues/{venueId}")
public class AvailabilityController {
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public com.example.connect_sphere.common.web.ApiError invalidBody() {
        return com.example.connect_sphere.common.web.ApiError.of("Enter valid calendar dates and times with timezone offsets, and valid request fields.");
    }
    private final AvailabilityService service;
    public AvailabilityController(AvailabilityService service) { this.service = service; }
    @GetMapping("/unavailability") public List<Period> list(@PathVariable UUID venueId) { return service.list(venueId); }
    @PostMapping("/unavailability/preview") public Preview preview(@PathVariable UUID venueId, @RequestBody Request request) {
        return service.preview(venueId, request);
    }
    @PostMapping("/unavailability") @ResponseStatus(HttpStatus.CREATED)
    public Period create(@PathVariable UUID venueId, @AuthenticationPrincipal Jwt jwt, @RequestBody Request request) {
        return service.create(venueId, UUID.fromString(jwt.getSubject()), request);
    }
    @GetMapping("/schedule") public Schedule schedule(@PathVariable UUID venueId) { return service.schedule(venueId); }
    @GetMapping("/occupancy-settings") public Settings settings(@PathVariable UUID venueId) { return service.settings(venueId); }
    @PutMapping("/occupancy-settings") public Settings settings(@PathVariable UUID venueId, @RequestBody Settings settings) {
        return service.updateSettings(venueId, settings);
    }
}
