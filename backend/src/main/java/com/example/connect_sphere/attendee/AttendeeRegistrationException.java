package com.example.connect_sphere.attendee;

public class AttendeeRegistrationException extends RuntimeException {
    private final int status;

    public AttendeeRegistrationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
