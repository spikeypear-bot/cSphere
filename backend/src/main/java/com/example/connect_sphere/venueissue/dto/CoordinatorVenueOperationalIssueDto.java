package com.example.connect_sphere.venueissue.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CoordinatorVenueOperationalIssueDto(
        UUID issueId,
        UUID venueId,
        String venueAddress,
        String description,
        OffsetDateTime affectedFrom,
        OffsetDateTime affectedUntil,
        OffsetDateTime createdAt,
        List<OverlappingEventDto> overlappingEvents) {

    public record OverlappingEventDto(
            UUID eventId,
            String eventName,
            OffsetDateTime startDatetime,
            OffsetDateTime endDatetime) {
    }
}
