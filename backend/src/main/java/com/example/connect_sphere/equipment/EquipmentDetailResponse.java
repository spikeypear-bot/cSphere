package com.example.connect_sphere.equipment;

import java.util.List;
import java.util.UUID;

/**
 * D32: one equipment kind for the Technical Support console. {@code units} is
 * empty for a kind not tracked by serial number. Each unit's {@code status}
 * is its status now (a Faulty or Unavailable block covering the present
 * moment, else Available); {@code statusPeriods} are all its TS03 blocks,
 * earliest first.
 */
public record EquipmentDetailResponse(
        UUID equipmentId,
        String equipmentName,
        boolean serialised,
        int totalQuantity,
        List<Unit> units) {

    public record Unit(String serialNumber, EquipmentStatus status, List<StatusPeriodResponse> statusPeriods) {
    }
}
