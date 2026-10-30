package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.UUID;

/** AT04: own registration history and current event information, explicitly separated. */
public record AttendeeRegistrationDetailsDto(
        UUID registrationId, String reference, OffsetDateTime registeredAt, String registrationStatus,
        UUID eventId, String eventName, OffsetDateTime startDatetime, OffsetDateTime endDatetime,
        String eventStatus, boolean eventDetailsAvailable, boolean withdrawalAvailable, String withdrawalReason) { }
