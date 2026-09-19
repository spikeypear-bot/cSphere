package com.example.connect_sphere.equipment;

import java.util.UUID;

// What we send to the frontend for one physical unit.
public record EquipmentUnitResponse(
        UUID equipmentId,
        String equipmentName,
        String serialNumber,
        EquipmentStatus status) {

    static EquipmentUnitResponse from(SerialisedEquipment unit) {
        return new EquipmentUnitResponse(
                unit.getId().getEquipmentId(),
                unit.getEquipment().getName(),
                unit.getId().getSerialNumber(),
                unit.getStatus());
    }
}