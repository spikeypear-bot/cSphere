package com.example.connect_sphere.venuemap;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

public final class VenueMapDto {
    private VenueMapDto() { }

    /** Counts only; opening the catalogue never fetches internal event details. */
    public record Activity(UUID venueId, int eventCount, int pendingBookingCount, int matchingBookingCount) { }

    /** One row per booking, including separate bookings of the same event. */
    public record Booking(UUID bookingId, UUID eventId, String eventName, VenueBookingStatus status,
            OffsetDateTime startDatetime, OffsetDateTime endDatetime,
            OffsetDateTime holdExpiresAt, OffsetDateTime serverTime, boolean past) { }
}
