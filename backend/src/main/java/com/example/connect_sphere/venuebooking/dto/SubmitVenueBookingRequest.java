package com.example.connect_sphere.venuebooking.dto;

import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.List;

/** EC03: the venue, optional notes for Venue Staff, and, when the venue
 * lacks a requested accessibility feature, why it is still being requested. */
public record SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime) {
    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote) {
        this(venueId, bookingNotes, suitabilityNote, null, null, null);
    }

    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId) {
        this(venueId, bookingNotes, suitabilityNote, replacesBookingId, null, null);
    }

    public record Batch(List<SubmitVenueBookingRequest> bookings) {}
}
