package com.example.connect_sphere.venuebooking.dto;

import java.util.UUID;

/** EC03: the venue, optional notes for Venue Staff, and, when the venue
 * lacks a requested accessibility feature, why it is still being requested. */
public record SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote) {
}
