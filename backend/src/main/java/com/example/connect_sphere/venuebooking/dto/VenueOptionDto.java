package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.example.connect_sphere.venue.dto.VenueDto;

/**
 * One venue in EC03's shortlist, already judged against the event.
 *
 * @param verdict {@code suitable}, {@code needs_justification} (bookable
 *     with a written reason) or {@code blocked} (cannot be requested)
 * @param reasons plain-language lines explaining the verdict
 */
public record VenueOptionDto(
        VenueDto venue,
        String verdict,
        boolean capacityOk,
        int spareCapacity,
        List<String> missingAccessibility,
        List<String> missingFacilities,
        List<UnavailablePeriodDto> unavailablePeriods,
        List<ConflictDto> conflicts,
        List<String> reasons) {

    public record UnavailablePeriodDto(String description, OffsetDateTime affectedFrom, OffsetDateTime affectedUntil) {
    }

    /** An approved booking that overlaps the event's time. */
    /** ECL-C7: another event's booking is shown only as "Booked by another
     * event" with its period, never its name or organiser. */
    public record ConflictDto(String description, OffsetDateTime startDatetime, OffsetDateTime endDatetime) {
        public static final String ANOTHER_EVENT = "Booked by another event";

        private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter
                .ofPattern("d MMM yyyy, h:mm a", java.util.Locale.ENGLISH);

        /** "Booked by another event, 10 Mar 2027, 9:00 AM – 12:00 PM (Singapore time)". */
        public String sentence() {
            java.time.ZoneId singapore = java.time.ZoneId.of("Asia/Singapore");
            return description + ", " + WHEN.format(startDatetime.atZoneSameInstant(singapore)) + " – "
                    + WHEN.format(endDatetime.atZoneSameInstant(singapore)) + " (Singapore time)";
        }
    }
}
