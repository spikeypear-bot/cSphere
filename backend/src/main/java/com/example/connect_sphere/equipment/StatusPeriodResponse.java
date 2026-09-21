package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.UUID;

public record StatusPeriodResponse(UUID id, EquipmentStatus status, Instant start, Instant end) {

    static StatusPeriodResponse from(EquipmentStatusPeriod p) {
        return new StatusPeriodResponse(
                p.getId(), p.getStatus(), p.getPeriodStart(), p.getPeriodEnd());
    }
}