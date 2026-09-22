package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

/** Thrown when approve/reject is attempted on a request that isn't {@code
 * pending} — either it hasn't been submitted yet, or a decision has already
 * been recorded. Deliberately distinct from {@link EventRequestNotEditableException}
 * (which is about an Organiser editing their own draft): this is a
 * Coordinator-side conflict, and doubling as "already decided" is exactly
 * what stops EO09's "receives at most one notification per successfully
 * recorded status-change event" from being violated by a duplicate
 * approve/reject call racing or retrying. */
public class EventRequestNotPendingException extends RuntimeException {
    public EventRequestNotPendingException(UUID requestId, EventRequestStatus status) {
        super("Event request " + requestId + " is not awaiting review (status: " + status + ")");
    }
}
