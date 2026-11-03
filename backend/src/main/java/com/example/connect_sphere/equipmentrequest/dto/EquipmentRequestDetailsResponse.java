package com.example.connect_sphere.equipmentrequest.dto;

import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestLine;
import java.util.List;
import java.util.UUID;

public record EquipmentRequestDetailsResponse(
        UUID requestId,
        UUID eventId,
        String status,
        String technicalRequirement,
        String rejectReason,
        String attentionReason,
        List<EquipmentRequestLineResponse> lines,
        /** EC-NEW3 (D40): replaces the event's approved requests once Technical Support approve it. */
        boolean revision) {

    public static EquipmentRequestDetailsResponse from(
            EquipmentRequest request, List<EquipmentRequestLine> lines) {
        return new EquipmentRequestDetailsResponse(
                request.getId(),
                request.getEventId(),
                request.getStatus().name(),
                request.getTechnicalRequirement(),
                request.getRejectReason(),
                request.getAttentionReason(),
                lines.stream().map(EquipmentRequestLineResponse::from).toList(),
                request.isRevision());
    }
}
