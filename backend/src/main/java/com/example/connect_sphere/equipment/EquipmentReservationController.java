package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentReservationController {

    private final EquipmentReservationService service;

    public EquipmentReservationController(EquipmentReservationService service) {
        this.service = service;
    }

    // AC 3+4: availability for the equipment listed under one equipment request.
    @GetMapping("/requests/{requestId}/availability")
    public List<EquipmentAvailabilityResponse> availability(
            @PathVariable UUID requestId,
            @RequestParam Instant start,
            @RequestParam Instant end) {
        return service.availabilityForEvent(requestId, start, end);
    }

    // AC 5-9: reserve.
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentReservationResponse reserve(@RequestBody ReserveEquipmentRequest request) {
        return service.reserve(request);
    }

    // AC 11: reservations for an event.
    @GetMapping("/events/{eventId}/reservations")
    public List<EquipmentReservationResponse> reservationsForEvent(@PathVariable UUID eventId) {
        return service.reservationsForEvent(eventId);
    }
}