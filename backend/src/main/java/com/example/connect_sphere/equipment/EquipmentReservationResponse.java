package com.example.connect_sphere.equipment;

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
        Instant loanedUntil) {

    /** Whether this reservation's loan window covers the whole of {@code start}
     * to {@code end} (EC-NEW3's equipment impact, TS11 AC4). */
    public boolean covers(Instant start, Instant end) {
        return loanedFrom != null && loanedUntil != null
                && !loanedFrom.isAfter(start) && !loanedUntil.isBefore(end);
    }

    static EquipmentReservationResponse from(EquipmentLog log, String equipmentName) {
        return new EquipmentReservationResponse(
                log.getId(), log.getEventId(), log.getEquipmentId(), equipmentName,
                log.getQuantity(), log.getSerialNumber(), log.getLoanedFrom(), log.getLoanedUntil());
    }
}