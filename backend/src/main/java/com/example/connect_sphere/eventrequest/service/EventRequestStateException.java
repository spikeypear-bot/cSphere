package com.example.connect_sphere.eventrequest.service;

/** An action that does not fit the request's current status, with a message
 * written for the person who tried it (EC01: "is told why"), e.g. approving a
 * request that is still waiting for the organiser's clarification. */
public class EventRequestStateException extends RuntimeException {
    public EventRequestStateException(String message) {
        super(message);
    }
}
