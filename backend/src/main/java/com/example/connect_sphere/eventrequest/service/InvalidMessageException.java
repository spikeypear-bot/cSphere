package com.example.connect_sphere.eventrequest.service;

/** A clarification message (EC01) or organiser response (EO26) that is blank,
 * whitespace-only, or over the 2000-character limit — or a flagged field name
 * that is not one of the request's fields. */
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException(String message) {
        super(message);
    }
}
