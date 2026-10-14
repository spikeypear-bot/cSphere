package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Only the details needed to explain an approval conflict. */
public record VenueBookingConflictDto(UUID bookingId, String eventName,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime,
        int setupDurationMinutes, int turnaroundDurationMinutes,
        OffsetDateTime effectiveStart, OffsetDateTime effectiveEnd) {
    public static VenueBookingConflictDto of(UUID id, String name, OffsetDateTime start, OffsetDateTime end,
            int setup, int turnaround) {
        return new VenueBookingConflictDto(id, name, start, end, setup, turnaround,
                start.minusMinutes(setup), end.plusMinutes(turnaround));
    }

    public static VenueBookingConflictDto from(com.example.connect_sphere.venueavailability.AvailabilityModels.Booking b) {
        return new VenueBookingConflictDto(b.bookingId(), b.eventName(), b.startDateTime(), b.endDateTime(),
                Math.toIntExact(java.time.Duration.between(b.effectiveStart(), b.startDateTime()).toMinutes()),
                Math.toIntExact(java.time.Duration.between(b.endDateTime(), b.effectiveEnd()).toMinutes()),
                b.effectiveStart(), b.effectiveEnd());
    }
}
