package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Explicit attendee-safe projection; never serialize the internal Event entity. */
public record AttendeeEventDetailsDto(
        UUID eventId,
        String eventName,
        String description,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        String status,
        OffsetDateTime updatedAt,
        List<String> accessibilityInformation,
        Boolean registrationRequired,
        OffsetDateTime registrationOpensAt,
        OffsetDateTime registrationClosesAt,
        Integer registrationCapacity,
        String attendeeInstructions,
        List<AttendeeVenue> venues) {

    public record AttendeeVenue(UUID venueId, String address, boolean unavailable) { }
}
