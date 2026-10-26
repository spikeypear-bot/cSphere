package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

/** EC03: a booking request as the coordinator sees it on their event. */
public record EventVenueBookingDto(
        UUID bookingId,
        VenueBookingStatus status,
        UUID venueId,
        String venueAddress,
        Integer venueCapacity,
        String bookingNotes,
        String suitabilityNote,
        String rejectReason,
        OffsetDateTime submittedAt,
        String submittedBy, boolean requiresAlternative, UUID replacesBookingId,
        UUID alternativeVenueId,
        String alternativeVenueAddress,
        String alternativeArrangement,
        OffsetDateTime startDatetime,
        OffsetDateTime endDatetime, BookingTimingDto timing, java.util.List<com.example.connect_sphere.venuebooking.hold.VenueHold> holds, String operatingInformation,
        java.util.List<com.example.connect_sphere.venue.dto.VenueOperatingHourDto> operatingHours, BookingRequirements requirements, UUID requirementId, boolean requirementOutstanding) {
}
