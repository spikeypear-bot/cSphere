package com.example.connect_sphere.venuebooking.hold;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Reserved times are complete occupied times, with buffers already applied. */
public record VenueHold(UUID holdId, UUID bookingId, UUID venueId, UUID eventId, String eventName,
        OffsetDateTime reservedStart, OffsetDateTime reservedEnd, OffsetDateTime expiresAt,
        String status, UUID createdBy, OffsetDateTime createdAt, OffsetDateTime endedAt, OffsetDateTime serverTime) {}
