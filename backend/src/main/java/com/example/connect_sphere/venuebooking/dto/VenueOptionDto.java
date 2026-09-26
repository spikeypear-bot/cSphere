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
        List<ConflictDto> conflicts,
        List<String> reasons) {

    /** A confirmed booking that overlaps the event's time. */
    public record ConflictDto(String eventName, OffsetDateTime startDatetime, OffsetDateTime endDatetime) {
    }
}
