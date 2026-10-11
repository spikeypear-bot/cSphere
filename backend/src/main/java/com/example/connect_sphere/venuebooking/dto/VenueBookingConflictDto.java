package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Only the details needed to explain an approval conflict. */
public record VenueBookingConflictDto(UUID bookingId, String eventName,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime) {}
