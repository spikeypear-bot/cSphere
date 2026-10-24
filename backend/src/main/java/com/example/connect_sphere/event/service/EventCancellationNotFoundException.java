package com.example.connect_sphere.event.service;

import java.util.UUID;

/** EO07: no such cancellation request for this event. 404. */
public class EventCancellationNotFoundException extends RuntimeException {
    public EventCancellationNotFoundException(UUID cancellationRequestId) {
        super("Cancellation request " + cancellationRequestId + " was not found for this event.");
    }
}
