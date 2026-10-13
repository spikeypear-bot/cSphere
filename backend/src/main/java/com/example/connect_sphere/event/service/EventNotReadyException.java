package com.example.connect_sphere.event.service;

import java.util.List;

/** EC08: the event cannot move on yet; {@code blockers} lists every reason. */
public class EventNotReadyException extends RuntimeException {
    private final List<String> blockers;

    public EventNotReadyException(String message, List<String> blockers) {
        super(message);
        this.blockers = List.copyOf(blockers);
    }

    public List<String> getBlockers() {
        return blockers;
    }
}
