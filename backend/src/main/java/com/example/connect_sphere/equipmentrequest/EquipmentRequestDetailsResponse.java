package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;

public record EquipmentRequestDetailsResponse(
        UUID requestId,
        UUID eventId,
        String status,
        String technicalRequirement,
        List<EquipmentRequestLineResponse> lines) {

    static EquipmentRequestDetailsResponse from(
            EquipmentRequest request, List<EquipmentRequestLine> lines) {
        return new EquipmentRequestDetailsResponse(
                request.getId(),
                request.getEventId(),
                request.getStatus().name(),
                request.getTechnicalRequirement(),
                lines.stream().map(EquipmentRequestLineResponse::from).toList());
    }
}
