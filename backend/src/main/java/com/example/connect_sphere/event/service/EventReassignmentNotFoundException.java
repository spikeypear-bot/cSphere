package com.example.connect_sphere.event.service;

import java.util.UUID;

public class EventReassignmentNotFoundException extends RuntimeException {
    public EventReassignmentNotFoundException(UUID requestId) {
        super("Reassignment request " + requestId + " was not found.");
    }
}
