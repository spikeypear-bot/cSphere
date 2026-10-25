package com.example.connect_sphere.equipmentdamage.dto;

import java.time.Instant;
import java.util.UUID;

/** TS09 AC16–AC20: damage affecting one event, as its coordinator (or the Lead) sees it. */
public record EventEquipmentDamageDto(
        UUID outcomeId,
        UUID reportId,
        UUID equipmentId,
        String equipmentName,
        String serialNumber,
        int damagedQuantity,
        String description,
        Instant periodStart,
        Instant periodEnd,
        Instant reportedAt,
        /** Null while Technical Support is still deciding. */
        String outcome,
        int shortfall,
        String replacementSerialNumber,
        Instant decidedAt,
        String resolution,
        String resolutionNote,
        Instant resolvedAt,
        UUID additionalRequestId,
        String additionalRequestStatus,
        /** AC19: an additional request can be submitted for this shortfall now. */
        boolean canRequestAdditional,
        /** AC20: the shortfall can be resolved with a note now. */
        boolean canResolve) {
}
