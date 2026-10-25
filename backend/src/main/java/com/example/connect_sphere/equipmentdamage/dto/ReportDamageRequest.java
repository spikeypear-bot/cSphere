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
 * TS03 block on the unit when the report closes. {@code markFaulty: true} is
 * the older way of asking for {@code FAULTY} and is still accepted.
 */
public record ReportDamageRequest(
        UUID equipmentId,
        String serialNumber,
        Integer quantity,
        String description,
        Instant periodStart,
        Instant periodEnd,
        Boolean markFaulty,
        String blockStatus) {

    /**
     * The block asked for, or null. 400 for an unknown status, or for
     * {@code markFaulty: true} together with a different {@code blockStatus}.
     */
    public DamageBlockStatus requestedBlock() {
        boolean faulty = Boolean.TRUE.equals(markFaulty);
        String raw = blockStatus == null ? "" : blockStatus.strip();
        if (raw.isEmpty()) {
            return faulty ? DamageBlockStatus.FAULTY : null;
        }
        DamageBlockStatus block;
        try {
            block = DamageBlockStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "blockStatus must be FAULTY, UNAVAILABLE or left out.");
        }
        if (faulty && block != DamageBlockStatus.FAULTY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "markFaulty asks for FAULTY, but blockStatus asks for " + block + ". Send one of them.");
        }
        return block;
    }
}
