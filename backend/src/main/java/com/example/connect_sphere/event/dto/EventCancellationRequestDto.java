package com.example.connect_sphere.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.event.entity.EventCancellationStatus;

/** EO07: one cancellation request as the organiser, the assigned coordinator
 * and the Lead see it. Names, not user ids, as on the timeline. */
public record EventCancellationRequestDto(
        UUID cancellationRequestId,
        UUID eventId,
        String eventName,
        String requesterName,
        String reason,
        EventCancellationStatus status,
        OffsetDateTime requestedAt,
        String decidedByName,
        OffsetDateTime decidedAt,
        String decisionReason) {
}
