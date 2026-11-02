package com.example.connect_sphere.equipment.dto;

import com.example.connect_sphere.equipment.entity.EquipmentStatus;
import com.example.connect_sphere.equipment.entity.SerialisedEquipment;
import java.util.UUID;

// What we send to the frontend for one physical unit.
public record EquipmentUnitResponse(
        UUID equipmentId,
        String equipmentName,
        String serialNumber,
        EquipmentStatus status) {

    public static EquipmentUnitResponse from(SerialisedEquipment unit, EquipmentStatus status) {
        return new EquipmentUnitResponse(
                unit.getId().getEquipmentId(),
                unit.getEquipment().getName(),
                unit.getId().getSerialNumber(),
                status);
    }
}