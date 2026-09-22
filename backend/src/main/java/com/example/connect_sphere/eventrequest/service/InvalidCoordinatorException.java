package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

/** Thrown when assign-coordinator names a user who doesn't exist or isn't an
 * Event Coordinator. Kept distinct from a generic 404/validation error so the
 * message stays specific to what actually went wrong for whoever is doing
 * the assigning. */
public class InvalidCoordinatorException extends RuntimeException {
    public InvalidCoordinatorException(UUID userId) {
        super("User " + userId + " is not an Event Coordinator");
    }

    public InvalidCoordinatorException() {
        super("A coordinator must be specified");
    }
}
