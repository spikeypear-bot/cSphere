package com.example.connect_sphere.equipment.dto;

import com.example.connect_sphere.equipment.entity.EquipmentStatus;
import com.example.connect_sphere.equipment.entity.EquipmentStatusPeriod;
import java.time.Instant;
import java.util.UUID;

public record StatusPeriodResponse(UUID id, EquipmentStatus status, Instant start, Instant end) {

    public static StatusPeriodResponse from(EquipmentStatusPeriod p) {
        return new StatusPeriodResponse(
                p.getId(), p.getStatus(), p.getPeriodStart(), p.getPeriodEnd());
    }
}