package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

/** Only venue-related event data; no organiser ownership or private request fields. */
public record VenueBookingDto(UUID bookingId, VenueBookingStatus status, VenueDto venue,
        EventRequirements event) {
    public record EventRequirements(UUID eventId, String eventName, OffsetDateTime startDatetime,
            OffsetDateTime endDatetime, Integer expectedAttendance, String venueRequirements,
            List<String> accessibilityNeeds, String equipmentRequirements) {}
}
