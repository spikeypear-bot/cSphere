package com.example.connect_sphere.venuebooking.service;

/** EC03: the request itself can't be accepted as entered (no venue, over
 * capacity, overlapping a confirmed booking, justification missing, text too
 * long). The message is written for the coordinator. */
public class InvalidVenueBookingException extends RuntimeException {
    public InvalidVenueBookingException(String message) {
        super(message);
    }
}
