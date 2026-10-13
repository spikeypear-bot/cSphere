package com.example.connect_sphere.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.common.status.StatusView;

/**
 * EO04: what an Event Organiser may see of their event's arrangements, and
 * nothing more. Built field by field rather than from the internal Event, so
 * internal notes, holds, setup/turnaround buffers and rejected requests can
 * never leak into it by accident.
 *
 * @param view {@code not_confirmed} (no arrangements shown yet),
 *             {@code confirmed}, {@code reconfirming} (was confirmed, an
 *             arrangement changed), {@code completed} or {@code cancelled}
 */
public record OrganiserArrangementsDto(
        UUID eventId,
        String eventName,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        Integer expectedAttendance,
        StatusView journeyStatus,
        String view,
        String coordinatorName,
        String coordinatorEmail,
        List<String> accessibilityNeeds,
        List<Venue> venues,
        List<Equipment> equipment,
        OffsetDateTime safetyApprovedAt,
        OffsetDateTime firstConfirmedAt,
        OffsetDateTime confirmedAt) {

    /** @param noLongerAvailable the venue became unavailable after confirmation */
    public record Venue(UUID venueId, String name, Integer capacity, List<String> layouts,
            List<String> accessibilities, List<String> facilities, String operatingInformation,
            boolean noLongerAvailable) {
    }

    public record Equipment(String name, int quantity) {
    }
}
