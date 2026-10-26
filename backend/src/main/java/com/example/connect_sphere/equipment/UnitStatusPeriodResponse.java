package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.UUID;

/** D32: one TS03 block with the unit it is on, for the Technical Support console's list. */
public record UnitStatusPeriodResponse(
        UUID id,
        UUID equipmentId,
        String equipmentName,
        String serialNumber,
        EquipmentStatus status,
        Instant start,
        Instant end) {

    static UnitStatusPeriodResponse from(EquipmentStatusPeriod p, String equipmentName) {
        return new UnitStatusPeriodResponse(p.getId(), p.getEquipmentId(), equipmentName, p.getSerialNumber(),
                p.getStatus(), p.getPeriodStart(), p.getPeriodEnd());
    }
}
