package com.example.connect_sphere.venuebooking.service;

import java.util.List;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.venue.entity.Venue;

/**
 * How well one venue fits one event, from the data both actually record.
 *
 * <p>Capacity and accessibility are checked because both are structured on
 * the event and the venue. Facilities are not: an event records its venue
 * needs only as free text, so there is nothing to compare automatically.
 * That gap is EC05's to close (a structured "required facilities" field);
 * until then the coordinator reads the requirements next to the venue's
 * facilities on the booking page.
 *
 * <p>Week 4 'Venue Suitability Checking': a venue "should not normally be
 * treated as suitable when the expected attendance exceeds its capacity or a
 * required facility is unavailable". Capacity blocks outright (attendance
 * equal to capacity fits); a missing accessibility feature needs a written
 * justification for Venue Staff. Whether exceptions are ever allowed is an
 * open customer Q&A question, and changing the answer means changing only
 * this class.
 */
public record VenueSuitability(
        int expectedAttendance,
        int capacity,
        List<String> missingAccessibility) {

    public static VenueSuitability of(Event event, Venue venue) {
        List<String> required = event.getAccessibilityNeeds() == null ? List.of()
                : event.getAccessibilityNeeds().stream().filter(need -> !"none".equals(need)).toList();
        List<String> offered = venue.getVenueAccessibilities() == null ? List.of() : venue.getVenueAccessibilities();
        return new VenueSuitability(
                event.getExpectedAttendance() == null ? 0 : event.getExpectedAttendance(),
                venue.getVenueCapacity() == null ? 0 : venue.getVenueCapacity(),
                required.stream().filter(need -> !offered.contains(need)).toList());
    }

    public boolean capacityOk() {
        return expectedAttendance <= capacity;
    }

    public boolean needsJustification() {
        return !missingAccessibility.isEmpty();
    }
}
