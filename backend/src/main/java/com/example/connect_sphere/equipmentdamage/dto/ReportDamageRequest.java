package com.example.connect_sphere.equipmentdamage.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * TS09 AC1–AC4. A serialised kind names the unit ({@code serialNumber});
 * a non-serialised kind gives how many units are damaged ({@code quantity}).
 * {@code periodEnd} is optional: no end means the damage runs on.
 */
public record ReportDamageRequest(
        UUID equipmentId,
        String serialNumber,
        Integer quantity,
        String description,
        Instant periodStart,
        Instant periodEnd,
        Boolean markFaulty) {

    public boolean wantsFaulty() {
        return Boolean.TRUE.equals(markFaulty);
    }
}
