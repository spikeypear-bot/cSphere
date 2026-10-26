package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AttendeeRegistrationDto(UUID registrationId, String reference, UUID eventId,
        String eventName, String status, OffsetDateTime registeredAt) { }
