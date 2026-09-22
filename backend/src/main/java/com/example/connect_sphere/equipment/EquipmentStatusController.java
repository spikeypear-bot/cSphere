package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class EquipmentStatusController {

    private final EquipmentStatusService service;

    public EquipmentStatusController(EquipmentStatusService service) {
        this.service = service;
    }

    // Every unit's status for a viewed period.
    @GetMapping("/units")
    public List<EquipmentUnitResponse> listUnits(
            @RequestParam("start") Instant start,
            @RequestParam("end") Instant end) {
        return service.listUnits(start, end);
    }

    // All blocks for one unit.
    @GetMapping("/{equipmentId}/units/{serialNumber}/periods")
    public List<StatusPeriodResponse> listPeriods(
            @PathVariable("equipmentId") UUID equipmentId,
            @PathVariable("serialNumber") String serialNumber) {
        return service.listPeriods(equipmentId, serialNumber);
    }

    // Add a block.
    @PostMapping("/{equipmentId}/units/{serialNumber}/periods")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusPeriodResponse addPeriod(
            @PathVariable("equipmentId") UUID equipmentId,
            @PathVariable("serialNumber") String serialNumber,
            @RequestBody AddStatusPeriodRequest request) {
        return service.addPeriod(
                equipmentId, serialNumber, request.status(), request.start(), request.end());
    }

    // Remove a block = the unit is available again.
    @DeleteMapping("/periods/{periodId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePeriod(@PathVariable("periodId") UUID periodId) {
        service.removePeriod(periodId);
    }
}