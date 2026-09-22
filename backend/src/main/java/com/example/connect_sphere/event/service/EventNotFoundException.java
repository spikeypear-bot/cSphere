package com.example.connect_sphere.event.service;

import java.util.UUID;

/** Thrown when an event genuinely doesn't exist, or an Event Organiser asks
 * for one belonging to another organisation — same not-found-either-way
 * shape as EventRequestNotFoundException, for the same AU04 reason: a
 * cross-organisation event must be indistinguishable from a missing one. */
public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(UUID eventId) {
        super("Event not found: " + eventId);
    }
}
