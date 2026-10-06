package com.example.connect_sphere.venueavailability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AvailabilityModels {
    private AvailabilityModels() {}
    public record Period(UUID unavailabilityId, UUID venueId, OffsetDateTime startDateTime,
            OffsetDateTime endDateTime, String reason, OffsetDateTime createdAt) {}
    public record Booking(UUID bookingId, UUID eventId, UUID coordinatorId, String eventName,
            String status, OffsetDateTime startDateTime, OffsetDateTime endDateTime,
            OffsetDateTime effectiveStart, OffsetDateTime effectiveEnd) {}
    public record Request(OffsetDateTime startDateTime, OffsetDateTime endDateTime, String reason,
            List<UUID> acknowledgedBookingIds, String previewToken) {
        // Explicit strict ISO parsing rejects impossible dates instead of normalising them.
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Request fromJson(
                @com.fasterxml.jackson.annotation.JsonProperty("startDateTime") String start,
                @com.fasterxml.jackson.annotation.JsonProperty("endDateTime") String end,
                @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason,
                @com.fasterxml.jackson.annotation.JsonProperty("acknowledgedBookingIds") List<UUID> ids,
                @com.fasterxml.jackson.annotation.JsonProperty("previewToken") String token) {
            return new Request(start == null || start.isBlank() ? null : OffsetDateTime.parse(start),
                    end == null || end.isBlank() ? null : OffsetDateTime.parse(end), reason, ids, token);
        }
    }
    public record Preview(List<Booking> affectedBookings, String previewToken) {}
    public record Settings(Integer setupMinutes, Integer turnaroundMinutes) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Settings fromJson(
                @com.fasterxml.jackson.annotation.JsonProperty("setupMinutes") java.math.BigDecimal setup,
                @com.fasterxml.jackson.annotation.JsonProperty("turnaroundMinutes") java.math.BigDecimal turnaround) {
            try {
                return new Settings(setup == null ? null : setup.intValueExact(), turnaround == null ? null : turnaround.intValueExact());
            } catch (ArithmeticException e) {
                throw new com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException("Setup and turnaround must be whole minutes from 0 to 10080.");
            }
        }
    }
    public record Schedule(Settings settings, List<Booking> bookings, List<Period> unavailablePeriods) {}
}
