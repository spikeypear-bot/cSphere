package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Small attendee-safe event summary used to open the details page. */
public record AttendeeEventListItemDto(
        UUID eventId,
        String eventName,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        String status,
        Boolean registrationRequired,
        OffsetDateTime registrationOpensAt,
        OffsetDateTime registrationClosesAt,
        Integer registrationCapacity,
        Integer availablePlaces,
        String registrationStatus) { }
