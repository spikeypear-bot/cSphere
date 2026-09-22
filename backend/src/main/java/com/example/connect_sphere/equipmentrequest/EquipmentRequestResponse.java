package com.example.connect_sphere.equipmentrequest;

import java.time.Instant;
import java.util.UUID;

public record EquipmentRequestResponse(
        UUID requestId,
        UUID eventId,
        String eventName,
        Instant eventStart,
        Instant eventEnd,
        String technicalRequirement) {

    static EquipmentRequestResponse from(
            EquipmentRequest r, String eventName, Instant eventStart, Instant eventEnd) {
        return new EquipmentRequestResponse(
                r.getId(), r.getEventId(), eventName, eventStart, eventEnd, r.getTechnicalRequirement());
    }
}