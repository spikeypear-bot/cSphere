package com.example.connect_sphere.venuebooking.service;

/** EC03: the event is in no state to take a booking request (not in
 * Planning, or it already has an active one). */
public class VenueBookingStateException extends RuntimeException {
    public VenueBookingStateException(String message) {
        super(message);
    }
}
