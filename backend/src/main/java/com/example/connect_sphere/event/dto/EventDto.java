package com.example.connect_sphere.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read shape for an approved/confirmed event — EO09's "can view the
 * confirmed event arrangements" and the notification's own deep link both
 * land here. {@code coordinatorName}/{@code coordinatorEmail} are resolved
 * server-side (not just a bare {@code coordinatorId}) so the frontend never
 * needs a second round trip, and never needs to know how to look a user up —
 * only Event Coordinators may be looked up this way, and only via this one
 * read path. */
public record EventDto(
        UUID eventId,
        String eventName,
        String purpose,
        String description,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        Integer expectedAttendance,
        UUID venueId,
        List<String> accessibilityNeeds,
        Boolean registrationNeeds,
        String organisation,
        String venueRequirements,
        String equipmentRequirements,
        String status,
        String coordinatorName,
        String coordinatorEmail) {
}
