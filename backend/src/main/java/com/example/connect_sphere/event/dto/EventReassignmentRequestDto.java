package com.example.connect_sphere.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.event.entity.EventReassignmentStatus;

public record EventReassignmentRequestDto(
        UUID reassignmentRequestId,
        UUID eventId,
        String eventName,
        String organisation,
        OffsetDateTime eventStartDatetime,
        OffsetDateTime eventEndDatetime,
        UUID requesterUserId,
        String requesterName,
        String reason,
        EventReassignmentStatus status,
        OffsetDateTime requestedAt,
        OffsetDateTime decidedAt,
        String decisionReason,
        UUID coordinatorUserId,
        String coordinatorName) {
}
