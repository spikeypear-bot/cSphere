package com.example.connect_sphere.venueissue.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;

public record VenueOperationalIssueDto(
        UUID issueId,
        UUID venueId,
        String description,
        OffsetDateTime affectedFrom,
        OffsetDateTime affectedUntil,
        UUID createdBy,
        OffsetDateTime createdAt) {

    public static VenueOperationalIssueDto from(VenueOperationalIssue issue) {
        return new VenueOperationalIssueDto(
                issue.getIssueId(),
                issue.getVenueId(),
                issue.getDescription(),
                issue.getAffectedFrom(),
                issue.getAffectedUntil(),
                issue.getCreatedBy(),
                issue.getCreatedAt());
    }
}
