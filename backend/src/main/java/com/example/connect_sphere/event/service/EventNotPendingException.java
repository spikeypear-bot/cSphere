package com.example.connect_sphere.event.service;

import java.util.UUID;

import com.example.connect_sphere.event.entity.EventStatus;

/** Thrown when confirm() is attempted on an event that isn't {@code pending}
 * — already confirmed, cancelled, or completed. Same duplicate-notification
 * guard as EventRequestNotPendingException, for the same reason. */
public class EventNotPendingException extends RuntimeException {
    public EventNotPendingException(UUID eventId, EventStatus status) {
        super("Event " + eventId + " is not awaiting confirmation (status: " + status + ")");
    }
}
