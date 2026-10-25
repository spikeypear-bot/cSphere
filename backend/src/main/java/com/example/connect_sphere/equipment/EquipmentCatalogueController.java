package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentCatalogueController {

    private final EquipmentCatalogueService service;

    public EquipmentCatalogueController(EquipmentCatalogueService service) {
        this.service = service;
    }

    // Without from/to: the plain catalogue (organisers, coordinators, Technical
    // Support). With them: also each kind's availability for that window
    // (Technical Support only, SecurityConfig; D32).
    @GetMapping("/catalogue")
    public List<EquipmentCatalogueResponse> catalogue(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.catalogue(from, to);
    }

    // D32: one kind with its units and their status periods (Technical Support only).
    @GetMapping("/{equipmentId}")
    public EquipmentDetailResponse detail(@PathVariable UUID equipmentId) {
        return service.detail(equipmentId);
    }
}
