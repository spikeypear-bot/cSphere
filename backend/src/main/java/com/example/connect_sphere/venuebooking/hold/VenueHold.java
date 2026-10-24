package com.example.connect_sphere.venuebooking.hold;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Reserved times are complete occupied times, with buffers already applied. {@code reminderDueAt} (EC-C3) is
 * when the hold enters the reminder window (expiry minus venue.holds.reminder-lead), so screens use the server's rule. */
public record VenueHold(UUID holdId, UUID bookingId, UUID venueId, UUID eventId, String eventName,
        OffsetDateTime reservedStart, OffsetDateTime reservedEnd, OffsetDateTime expiresAt, OffsetDateTime reminderDueAt,
        String status, UUID createdBy, OffsetDateTime createdAt, OffsetDateTime endedAt, OffsetDateTime serverTime) {}
