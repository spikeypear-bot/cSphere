package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

/** EC03: a booking request as the coordinator sees it on their event. */
public record EventVenueBookingDto(
        UUID bookingId,
        VenueBookingStatus status,
        UUID venueId,
        String venueAddress,
        Integer venueCapacity,
        String bookingNotes,
        String suitabilityNote,
        String rejectReason,
        OffsetDateTime submittedAt,
        String submittedBy) {
}
