package com.example.connect_sphere.equipment.dto;

import com.example.connect_sphere.equipment.entity.EquipmentLog;
import java.time.Instant;
import java.util.UUID;

public record EquipmentReservationResponse(
        UUID logId,
        UUID eventId,
        UUID equipmentId,
        String equipmentName,
        int quantity,
        String serialNumber,
        Instant loanedFrom,
        Instant loanedUntil,
        /** D32: the event the reservation is for (null when it cannot be read). */
        String eventName,
        /** D33: an extra unit held beyond what the event requested. */
        boolean spare) {

    /** Whether this reservation's loan window covers the whole of {@code start}
     * to {@code end} (EC-NEW3's equipment impact, TS11 AC4). */
    public boolean covers(Instant start, Instant end) {
        // Built only from an EquipmentLog, whose loan window is NOT NULL (V2 equipment_logs).
        return !loanedFrom.isAfter(start) && !loanedUntil.isBefore(end);
    }

    public static EquipmentReservationResponse from(EquipmentLog log, String equipmentName, String eventName) {
        return new EquipmentReservationResponse(
                log.getId(), log.getEventId(), log.getEquipmentId(), equipmentName,
                log.getQuantity(), log.getSerialNumber(), log.getLoanedFrom(), log.getLoanedUntil(), eventName,
                log.isSpare());
    }
}
