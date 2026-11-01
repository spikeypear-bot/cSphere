package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Published when a coordinator approves an event change (EC-NEW3), inside the
 * approval's transaction. Listeners that tell staff about it run after commit,
 * so a pending, rejected or withdrawn change never reaches them (TS11 AC5).
 * Values are the changeable fields as ChangeFields keeps them (ISO times,
 * trimmed text), before and after.
 */
public record ChangeApproved(UUID changeRequestId, UUID eventId, Map<String, Object> previousValues,
        Map<String, Object> updatedValues, Set<String> changedFields, OffsetDateTime approvedAt) {
}
