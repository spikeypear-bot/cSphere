package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReadinessDto(
        UUID eventId,
        String approvalStatus,
        String approvalNote,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        int completedItems,
        int totalItems,
        List<ReadinessItemDto> items) {
}
