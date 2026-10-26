package com.example.connect_sphere.eventrequest.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;

/**
 * Input body for creating or updating a draft (EO01). Every field is optional —
 * that is the whole point of a draft — so this is intentionally not validated
 * with Bean Validation annotations; completeness is only checked at submission
 * time, in EventRequestService.submit().
 */
public record SaveEventRequestRequest(
        String eventName,
        String purpose,
        String description,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime,
        Integer expectedAttendance,
        String venueRequirements,
        String equipmentRequirements,
        List<AccessibilityFeature> accessibilityNeeds,
        List<Facility> requiredFacilities,
        Boolean registrationNeeds,
        Integer registrationCapacity,
        OffsetDateTime registrationOpensAt,
        OffsetDateTime registrationClosesAt,
        String attendeeInstructions) {

    // Compatibility for clients that still send a closing time: V36 defines
    // the closing time as event start, so a separate value is never persisted.
    public SaveEventRequestRequest {
        registrationClosesAt = startDatetime;
    }

    public SaveEventRequestRequest(String eventName, String purpose, String description,
            OffsetDateTime startDatetime, OffsetDateTime endDatetime, Integer expectedAttendance,
            String venueRequirements, String equipmentRequirements, List<AccessibilityFeature> accessibilityNeeds,
            List<Facility> requiredFacilities, Boolean registrationNeeds) {
        this(eventName, purpose, description, startDatetime, endDatetime, expectedAttendance, venueRequirements,
                equipmentRequirements, accessibilityNeeds, requiredFacilities, registrationNeeds, null, null, null, null);
    }

    public SaveEventRequestRequest(String eventName, String purpose, String description,
            OffsetDateTime startDatetime, OffsetDateTime endDatetime, Integer expectedAttendance,
            String venueRequirements, String equipmentRequirements,
            List<AccessibilityFeature> accessibilityNeeds, Boolean registrationNeeds) {
        this(eventName, purpose, description, startDatetime, endDatetime, expectedAttendance,
                venueRequirements, equipmentRequirements, accessibilityNeeds, List.of(), registrationNeeds,
                null, null, null, null);
    }
}
