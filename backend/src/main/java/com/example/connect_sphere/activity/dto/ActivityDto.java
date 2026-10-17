package com.example.connect_sphere.activity.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
        OffsetDateTime occurredAt,
        Map<String, String> fieldQuestions,
        Map<String, Object> fieldValues,
        /** EC-NEW5: "major" or "normal", decided once on the server. */
        String significance) {

    /** Before EC-NEW5 (no significance). */
    public ActivityDto(UUID activityId, String type, String actorName, String actorRole, String message,
            List<String> flaggedFields, String fromStatus, String toStatus, OffsetDateTime occurredAt,
            Map<String, String> fieldQuestions, Map<String, Object> fieldValues) {
        this(activityId, type, actorName, actorRole, message, flaggedFields, fromStatus, toStatus, occurredAt,
                fieldQuestions, fieldValues, "normal");
    }
}
