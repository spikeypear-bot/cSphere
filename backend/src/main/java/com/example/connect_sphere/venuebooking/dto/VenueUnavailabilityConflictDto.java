package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VenueUnavailabilityConflictDto(UUID unavailabilityId, String reason,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime) {}
