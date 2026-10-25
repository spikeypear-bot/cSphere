package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.UUID;

/**
 * TS02: one reservation. {@code serialNumber} is null for a quantity-only
 * reservation; for a serialised kind the server then picks free, working
 * units, one reservation per unit (D33). {@code spare} (null = false) marks
 * the whole reservation as spare units beyond what the event requested.
 */
public record ReserveEquipmentRequest(
        UUID eventId,
        UUID equipmentId,
        int quantity,
        String serialNumber,
        Instant start,
        Instant end,
        Boolean spare) {

    /** Not a spare (callers built before D33). */
    public ReserveEquipmentRequest(UUID eventId, UUID equipmentId, int quantity, String serialNumber,
            Instant start, Instant end) {
        this(eventId, equipmentId, quantity, serialNumber, start, end, false);
    }
}
