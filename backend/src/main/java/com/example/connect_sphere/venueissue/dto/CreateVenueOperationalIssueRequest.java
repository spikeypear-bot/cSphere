package com.example.connect_sphere.venueissue.dto;

import java.time.OffsetDateTime;

public record CreateVenueOperationalIssueRequest(
        String description,
        OffsetDateTime affectedFrom,
        OffsetDateTime affectedUntil) {
}
