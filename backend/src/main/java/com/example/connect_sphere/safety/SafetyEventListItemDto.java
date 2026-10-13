package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SafetyEventListItemDto(
        UUID eventId,
        String eventName,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        String venueAddress,
        String status) {
}
