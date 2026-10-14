package com.example.connect_sphere.venuebooking.dto;

import java.util.UUID;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

/** Each booking has its own requested window and captured occupancy durations. */
public record SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime,
        BigDecimal setupDurationMinutes, BigDecimal turnaroundDurationMinutes, String timingNote) {
    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote) {
        this(venueId, bookingNotes, suitabilityNote, null);
    }
    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId) {
        this(venueId, bookingNotes, suitabilityNote, replacesBookingId, null, null, null, null, null);
    }
    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId,
            OffsetDateTime startDatetime, OffsetDateTime endDatetime) {
        this(venueId, bookingNotes, suitabilityNote, replacesBookingId, startDatetime, endDatetime, null, null, null);
    }
    public SubmitVenueBookingRequest(UUID venueId, String bookingNotes, String suitabilityNote, UUID replacesBookingId,
            BigDecimal setupDurationMinutes, BigDecimal turnaroundDurationMinutes, String timingNote) {
        this(venueId, bookingNotes, suitabilityNote, replacesBookingId, null, null, setupDurationMinutes, turnaroundDurationMinutes, timingNote);
    }
    public record Batch(List<SubmitVenueBookingRequest> bookings) {}
}
