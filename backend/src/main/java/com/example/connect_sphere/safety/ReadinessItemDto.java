package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;

public record ReadinessItemDto(
        String itemKey,
        String label,
        boolean checked,
        String note,
        String checkedBy,
        OffsetDateTime checkedAt,
        String updatedBy,
        OffsetDateTime updatedAt) {
}
