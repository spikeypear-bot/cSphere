package com.example.connect_sphere.notification.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.notification.entity.NotificationChange;

/** Read shape for EO09/EO19. Deliberately carries only what those stories'
 * ACs say the recipient may see: an event reference, what happened, when —
 * never internal notes, workload information, or (per EO19) a previous
 * coordinator's details. {@code linkPath} is precomputed server-side (rather
 * than left for the frontend to guess from eventId/eventRequestId) so a
 * frontend change to routing can't silently break "access the relevant
 * event-request or event-details page from the notification". */
public record NotificationDto(
        UUID notificationId,
        String type,
        UUID eventRequestId,
        UUID eventId,
        String eventName,
        String newStatus,
        String reason,
        String coordinatorName,
        String coordinatorEmail,
        Boolean isReassignment,
        OffsetDateTime occurredAt,
        boolean read,
        String linkPath,
        String message,
        /** EC-NEW4: who did it ("ecl1") and their role ("ecl"); null when the system did. */
        String actorName,
        String actorRole,
        List<NotificationChange> changes,
        OffsetDateTime originalEventStartDatetime,
        OffsetDateTime originalEventEndDatetime) {
}
