package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.common.status.StatusView;

public record SafetyEventListItemDto(
        UUID eventId,
        String eventName,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        String venueAddress,
        String status,
        // EC-NEW2: the same status label every other role sees.
        StatusView journeyStatus) {
}
