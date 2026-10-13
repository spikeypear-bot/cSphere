package com.example.connect_sphere.event.service;

/** EC08: a reason or feedback that is blank, whitespace-only or too long. */
public class EventLifecycleValidationException extends RuntimeException {
    public EventLifecycleValidationException(String message) {
        super(message);
    }
}
