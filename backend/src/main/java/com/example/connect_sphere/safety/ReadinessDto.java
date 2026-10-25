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
        // SO-C5/SO-C6: the checklist items the latest rejection or request
        // for changes concerns, in checklist order; empty otherwise.
        List<String> flaggedItems,
        int completedItems,
        int totalItems,
        List<ReadinessItemDto> items) {
}
