package com.example.connect_sphere.activity.service;

import java.util.UUID;

import com.example.connect_sphere.activity.entity.ActivityType;

/**
 * EC-NEW5: something happened to an event that belongs in its change log.
 * Published by the service that did it, inside its own transaction;
 * {@link ChangeLogRecorder} writes the entry in that same transaction, so the
 * entry exists exactly when the change does (a failed action leaves none).
 *
 * @param actorUserId who did it, or null for "the signed-in user"
 */
public record EventChange(UUID eventId, ActivityType type, UUID actorUserId, String message) {
}
