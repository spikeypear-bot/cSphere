package com.example.connect_sphere.activity.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One timeline entry as a client sees it. Deliberately omits the actor's user
 * id and the audience list: the reader needs who and what, not internal keys. */
public record ActivityDto(
        UUID activityId,
        String type,
        String actorName,
        String actorRole,
        String message,
        List<String> flaggedFields,
        String fromStatus,
        String toStatus,
        OffsetDateTime occurredAt) {
}
