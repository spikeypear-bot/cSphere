package com.example.connect_sphere.eventrequest.service;

public class InvalidEventRequestScheduleException extends RuntimeException {
    public InvalidEventRequestScheduleException() {
        super("End date & time must be on or after start date & time.");
    }
}
