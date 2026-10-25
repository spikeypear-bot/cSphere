package com.example.connect_sphere.equipmentdamage.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.equipmentdamage.dto.EventEquipmentDamageDto;
import com.example.connect_sphere.equipmentdamage.dto.ResolveShortfallRequest;
import com.example.connect_sphere.equipmentdamage.service.EventEquipmentDamageService;

/** TS09 AC17–AC20: an event's damaged equipment, for its coordinator (and the Lead, read-only). */
@RestController
@RequestMapping("/api/events/{eventId}/equipment-damage")
public class EventEquipmentDamageController {

    private final EventEquipmentDamageService service;

    public EventEquipmentDamageController(EventEquipmentDamageService service) {
        this.service = service;
    }

    @GetMapping
    public List<EventEquipmentDamageDto> forEvent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        // The token's role claim is the UserRole name (TokenService); the Lead reads any event (ECL-C7).
        boolean lead = "ecl".equalsIgnoreCase(jwt.getClaimAsString("role"));
        return service.forEvent(UUID.fromString(jwt.getSubject()), lead, eventId);
    }

    @PostMapping("/{outcomeId}/resolve")
    public EventEquipmentDamageDto resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @PathVariable UUID outcomeId, @RequestBody ResolveShortfallRequest request) {
        return service.resolve(UUID.fromString(jwt.getSubject()), eventId, outcomeId, request);
    }
}
