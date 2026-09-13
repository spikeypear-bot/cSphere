package com.example.connect_sphere.eventrequest.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

/** Read shape returned to the frontend. Deliberately omits createdBy — no
 * real accounts exist yet (see EventRequest's class-level note), so there is
 * nothing meaningful to show for it. */
public record EventRequestDto(
        UUID requestId,
        Character requestType,
        UUID eventId,
        String eventName,
        String purpose,
        String description,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        Integer expectedAttendance,
        String venueRequirements,
        String equipmentRequirements,
        List<AccessibilityFeature> accessibilityNeeds,
        Boolean registrationNeeds,
        EventRequestStatus status,
        OffsetDateTime createdAt,
        String organisation) {
}
