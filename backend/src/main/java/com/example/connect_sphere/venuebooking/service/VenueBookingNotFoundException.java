package com.example.connect_sphere.venuebooking.service;

import java.util.UUID;

public class VenueBookingNotFoundException extends RuntimeException {
    public VenueBookingNotFoundException(UUID id) {
        super("Venue booking not found: " + id);
    }
}
