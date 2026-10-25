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
        Instant loanedUntil,
        /** D32: the event the reservation is for (null when it cannot be read). */
        String eventName,
        /** D33: an extra unit held beyond what the event requested. */
        boolean spare) {

    static EquipmentReservationResponse from(EquipmentLog log, String equipmentName, String eventName) {
        return new EquipmentReservationResponse(
                log.getId(), log.getEventId(), log.getEquipmentId(), equipmentName,
                log.getQuantity(), log.getSerialNumber(), log.getLoanedFrom(), log.getLoanedUntil(), eventName,
                log.isSpare());
    }
}
