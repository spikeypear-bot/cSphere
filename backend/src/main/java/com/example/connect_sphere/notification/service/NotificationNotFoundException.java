package com.example.connect_sphere.notification.service;

import java.util.UUID;

/** Thrown both when a notification genuinely doesn't exist, and when it
 * belongs to someone else — same not-found-either-way shape as
 * EventRequestNotFoundException, and for the same reason. */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(UUID notificationId) {
        super("Notification not found: " + notificationId);
    }
}
