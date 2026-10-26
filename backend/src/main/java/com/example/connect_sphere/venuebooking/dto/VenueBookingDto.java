package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

/** Only venue-related event data; no organiser ownership or private request fields. */
public record VenueBookingDto(UUID bookingId, VenueBookingStatus status, VenueDto venue,
        EventRequirements event, String bookingNotes, String suitabilityNote, OffsetDateTime submittedAt, String rejectReason,
        boolean requiresAlternative, UUID replacesBookingId,
        UUID alternativeVenueId, String alternativeVenueAddress, String alternativeArrangement, BookingTimingDto timing, List<com.example.connect_sphere.venuebooking.hold.VenueHold> holds, BookingRequirements requirements, BookingRequirements effectiveRequirements, List<RelatedBooking> relatedBookings,
        String currentCoordinatorName, String currentCoordinatorEmail) {
    public record RelatedBooking(UUID bookingId, UUID venueId, String venueAddress, VenueBookingStatus status,
            OffsetDateTime startDatetime, OffsetDateTime endDatetime, OffsetDateTime effectiveStart, OffsetDateTime effectiveEnd) {}
    public record EventRequirements(UUID eventId, String eventName, OffsetDateTime startDatetime,
            OffsetDateTime endDatetime, Integer expectedAttendance, String venueRequirements,
            List<String> accessibilityNeeds, String equipmentRequirements) {}
}
