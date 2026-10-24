package com.example.connect_sphere.equipmentdamage.controller;

import java.net.URI;
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

import com.example.connect_sphere.equipmentdamage.dto.DamageReportDto;
import com.example.connect_sphere.equipmentdamage.dto.DecideOutcomeRequest;
import com.example.connect_sphere.equipmentdamage.dto.ReportDamageRequest;
import com.example.connect_sphere.equipmentdamage.service.EquipmentDamageService;

/** TS09: Technical Support report damaged equipment and decide each affected event. */
@RestController
@RequestMapping("/api/equipment-damage-reports")
public class EquipmentDamageController {

    private final EquipmentDamageService service;

    public EquipmentDamageController(EquipmentDamageService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DamageReportDto> report(@AuthenticationPrincipal Jwt jwt,
            @RequestBody ReportDamageRequest request) {
        DamageReportDto saved = service.report(userIdOf(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/equipment-damage-reports/" + saved.reportId()))
                .body(saved);
    }

    @GetMapping
    public List<DamageReportDto> list() {
        return service.list();
    }

    @GetMapping("/{reportId}")
    public DamageReportDto get(@PathVariable UUID reportId) {
        return service.get(reportId);
    }

    @PostMapping("/{reportId}/outcomes/{outcomeId}")
    public DamageReportDto decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
            @PathVariable UUID outcomeId, @RequestBody DecideOutcomeRequest request) {
        return service.decide(userIdOf(jwt), reportId, outcomeId, request);
    }

    @PostMapping("/{reportId}/close")
    public DamageReportDto close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
        return service.close(userIdOf(jwt), reportId);
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
