package com.example.connect_sphere.venue.service;

import java.util.List;

/** Field-specific validation messages for catalogue creation. */
public class InvalidVenueException extends RuntimeException {
    public InvalidVenueException(List<String> errors) {
        super("Invalid venue: " + String.join("; ", errors));
    }
}
