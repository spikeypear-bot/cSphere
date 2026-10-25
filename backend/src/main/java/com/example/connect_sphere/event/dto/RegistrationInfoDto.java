package com.example.connect_sphere.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** EO10. registrationState: not_required, not_yet_open, open, closed or full. */
public record RegistrationInfoDto(UUID eventId, String eventName, boolean registrationRequired,
        String registrationState, Integer capacity, int confirmedCount, Integer placesRemaining,
        OffsetDateTime opensAt, OffsetDateTime closesAt, String message, List<Attendee> attendees) {

    /** status: confirmed, withdrawn or event_cancelled. */
    public record Attendee(UUID registrationId, String name, String reference, OffsetDateTime registeredAt,
            String status) {
    }
}
