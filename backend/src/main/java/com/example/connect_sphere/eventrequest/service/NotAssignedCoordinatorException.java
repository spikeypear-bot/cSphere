package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

/** Thrown when approve/reject/confirm is attempted by an Event Coordinator
 * who is not the one assigned to this request/event. A request must have a
 * coordinator assigned (EO19) before it can be decided — this is what makes
 * "the coordinator assigned to them" (SecurityConfig's own stated future
 * scoping rule) a real, enforced business rule rather than just a comment. */
public class NotAssignedCoordinatorException extends RuntimeException {
    /** The same words for a coordinator who never had it and one who was
     * reassigned (ECL-C7): neither learns anything about who has it now. */
    public static final String MESSAGE = "This is not assigned to you. If you were working on it, the "
            + "Event Coordinator Lead may have reassigned it to another coordinator.";

    private final UUID requestOrEventId;

    public NotAssignedCoordinatorException(UUID requestOrEventId) {
        super(MESSAGE);
        this.requestOrEventId = requestOrEventId;
    }

    public UUID getRequestOrEventId() {
        return requestOrEventId;
    }
}
