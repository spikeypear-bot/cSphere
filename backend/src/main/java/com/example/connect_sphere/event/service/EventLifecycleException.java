package com.example.connect_sphere.event.service;

/** EC08/EC-NEW2: an action that does not fit the event's current status, with
 * a message naming that status for the person who tried it. */
public class EventLifecycleException extends RuntimeException {
    public EventLifecycleException(String message) {
        super(message);
    }
}
