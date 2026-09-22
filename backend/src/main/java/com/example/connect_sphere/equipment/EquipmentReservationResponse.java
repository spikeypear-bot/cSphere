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

    static EquipmentReservationResponse from(EquipmentLog log, String equipmentName) {
        return new EquipmentReservationResponse(
                log.getId(), log.getEventId(), log.getEquipmentId(), equipmentName,
                log.getQuantity(), log.getSerialNumber(), log.getLoanedFrom(), log.getLoanedUntil());
    }
}