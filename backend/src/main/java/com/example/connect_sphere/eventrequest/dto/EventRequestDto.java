package com.example.connect_sphere.eventrequest.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.common.status.StatusView;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

/** Read shape returned to the frontend. Carries the creator's username, not
 * their id: the page shows "submitted by eo1" (or "by you"), and requests
 * submitted before the V13 timeline have no other record of who that was.
 * Only ever returned to the creator's own organisation or to Coordinators. */
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
        List<String> requiredFacilities,
        Boolean registrationNeeds,
        Integer registrationCapacity,
        OffsetDateTime registrationOpensAt,
        OffsetDateTime registrationClosesAt,
        String attendeeInstructions,
        EventRequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String organisation,
        UUID coordinatorId,
        String rejectionReason,
        String createdByName,
        /** EC-NEW2: the one status every screen shows. */
        StatusView journeyStatus) {

    /** Without the journey status (EC-NEW2), for callers built before it; the
     * mapper always fills it in for real responses. */
    public EventRequestDto(UUID requestId, Character requestType, UUID eventId, String eventName,
            String purpose, String description, OffsetDateTime startDatetime, OffsetDateTime endDatetime,
            Integer expectedAttendance, String venueRequirements, String equipmentRequirements,
            List<AccessibilityFeature> accessibilityNeeds, List<String> requiredFacilities, Boolean registrationNeeds,
            EventRequestStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            String organisation, UUID coordinatorId, String rejectionReason, String createdByName) {
        this(requestId, requestType, eventId, eventName, purpose, description, startDatetime, endDatetime,
                expectedAttendance, venueRequirements, equipmentRequirements, accessibilityNeeds,
                requiredFacilities, registrationNeeds, null, null, null, null, status, createdAt, updatedAt, organisation, coordinatorId,
                rejectionReason, createdByName, null);
    }

    public EventRequestDto(UUID requestId, Character requestType, UUID eventId, String eventName,
            String purpose, String description, OffsetDateTime startDatetime, OffsetDateTime endDatetime,
            Integer expectedAttendance, String venueRequirements, String equipmentRequirements,
            List<AccessibilityFeature> accessibilityNeeds, List<String> requiredFacilities, Boolean registrationNeeds,
            EventRequestStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            String organisation, UUID coordinatorId, String rejectionReason, String createdByName,
            StatusView journeyStatus) {
        this(requestId, requestType, eventId, eventName, purpose, description, startDatetime, endDatetime,
                expectedAttendance, venueRequirements, equipmentRequirements, accessibilityNeeds, requiredFacilities,
                registrationNeeds, null, null, null, null, status, createdAt, updatedAt, organisation, coordinatorId,
                rejectionReason, createdByName, journeyStatus);
    }

    public EventRequestDto(UUID requestId, Character requestType, UUID eventId, String eventName,
            String purpose, String description, OffsetDateTime startDatetime, OffsetDateTime endDatetime,
            Integer expectedAttendance, String venueRequirements, String equipmentRequirements,
            List<AccessibilityFeature> accessibilityNeeds, Boolean registrationNeeds,
            EventRequestStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            String organisation, UUID coordinatorId, String rejectionReason, String createdByName) {
        this(requestId, requestType, eventId, eventName, purpose, description, startDatetime, endDatetime,
                expectedAttendance, venueRequirements, equipmentRequirements, accessibilityNeeds,
                List.of(), registrationNeeds, null, null, null, null, status, createdAt, updatedAt, organisation, coordinatorId,
                rejectionReason, createdByName, null);
    }
}
