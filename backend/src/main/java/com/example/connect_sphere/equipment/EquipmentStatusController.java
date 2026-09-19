package com.example.connect_sphere.equipment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentStatusController {

    private final EquipmentStatusService service;

    public EquipmentStatusController(EquipmentStatusService service) {
        this.service = service;
    }

    // GET /api/equipment/units
    @GetMapping("/units")
    public List<EquipmentUnitResponse> listUnits() {
        return service.listUnits();
    }

    // PATCH /api/equipment/{equipmentId}/units/{serialNumber}/status
    @PatchMapping("/{equipmentId}/units/{serialNumber}/status")
    public EquipmentUnitResponse updateStatus(
            @PathVariable("equipmentId") UUID equipmentId,
            @PathVariable("serialNumber") String serialNumber,
            @RequestBody UpdateStatusRequest request) {
        return service.updateStatus(equipmentId, serialNumber, request.status());
    }
}