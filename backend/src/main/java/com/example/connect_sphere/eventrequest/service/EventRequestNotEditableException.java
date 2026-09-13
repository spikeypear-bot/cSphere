package com.example.connect_sphere.eventrequest.service;

import java.util.UUID;

import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

/** Thrown when an update or (re-)submit is attempted on a request that has
 * already left `draft` — EO01's "does not overwrite/re-open a submitted
 * request" boundary. */
public class EventRequestNotEditableException extends RuntimeException {
    public EventRequestNotEditableException(UUID requestId, EventRequestStatus status) {
        super("Event request " + requestId + " is no longer a draft (status: " + status + ")");
    }
}
