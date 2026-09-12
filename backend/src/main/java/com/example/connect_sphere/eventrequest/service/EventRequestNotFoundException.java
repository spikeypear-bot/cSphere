package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

/** Thrown both when a request genuinely doesn't exist, and when it belongs to a
 * different organisation — deliberately the same exception for both, so a
 * caller cannot tell the two apart (AU04: cross-organisation requests must be
 * neither visible nor distinguishable from missing ones). */
public class EventRequestNotFoundException extends RuntimeException {
    public EventRequestNotFoundException(UUID requestId) {
        super("Event request not found: " + requestId);
    }
}
