package com.example.connect_sphere.event.service;

import org.springframework.http.HttpStatus;

public class RegistrationPeriodException extends RuntimeException {
    private final HttpStatus status;

    public RegistrationPeriodException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
