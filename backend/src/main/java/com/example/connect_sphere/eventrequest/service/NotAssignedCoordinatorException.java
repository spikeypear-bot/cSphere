package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

/** Thrown when approve/reject/confirm is attempted by an Event Coordinator
 * who is not the one assigned to this request/event. A request must have a
 * coordinator assigned (EO19) before it can be decided — this is what makes
 * "the coordinator assigned to them" (SecurityConfig's own stated future
 * scoping rule) a real, enforced business rule rather than just a comment. */
public class NotAssignedCoordinatorException extends RuntimeException {
    public NotAssignedCoordinatorException(UUID requestOrEventId) {
        super("You are not the coordinator assigned to " + requestOrEventId);
    }
}
