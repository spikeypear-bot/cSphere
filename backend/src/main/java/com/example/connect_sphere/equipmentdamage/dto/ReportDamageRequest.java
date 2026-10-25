package com.example.connect_sphere.equipmentdamage.dto;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.equipmentdamage.entity.DamageBlockStatus;

/**
 * TS09 AC1–AC4. A serialised kind names the unit ({@code serialNumber});
 * a non-serialised kind gives how many units are damaged ({@code quantity}).
 * {@code periodEnd} is optional: no end means the damage runs on.
 *
 * <p>{@code blockStatus} ({@code FAULTY} or {@code UNAVAILABLE}) asks for a
 * TS03 block on the unit when the report closes. It is required for a
 * serialised unit and refused for a non-serialised kind.
 */
public record ReportDamageRequest(
        UUID equipmentId,
        String serialNumber,
        Integer quantity,
        String description,
        Instant periodStart,
        Instant periodEnd,
        String blockStatus) {

    /** The block asked for, or null. 400 for an unknown status. */
    public DamageBlockStatus requestedBlock() {
        String raw = blockStatus == null ? "" : blockStatus.strip();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return DamageBlockStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "blockStatus must be FAULTY, UNAVAILABLE or left out.");
        }
    }
}
