package com.example.connect_sphere.event.service;

/** EO07: the request or event is not in a state that allows this cancellation
 * step (wrong status, a decision already made, one already pending). 409. */
public class EventCancellationStateException extends RuntimeException {
    public EventCancellationStateException(String message) {
        super(message);
    }
}
