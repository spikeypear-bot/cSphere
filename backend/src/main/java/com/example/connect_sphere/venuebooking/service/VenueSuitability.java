package com.example.connect_sphere.venuebooking.service;

import java.util.List;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.venue.entity.Venue;

/**
 * How well one venue fits one event, from the data both actually record.
 *
 * <p>Capacity, accessibility, and facilities are checked from structured
 * event and venue fields so the coordinator receives an explainable result
 * before submitting a request.
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
        List<String> missingAccessibility,
        List<String> missingFacilities) {

    public static VenueSuitability of(Event event, Venue venue) {
        List<String> required = event.getAccessibilityNeeds() == null ? List.of()
                : event.getAccessibilityNeeds().stream().filter(need -> !"none".equals(need)).toList();
        List<String> offered = venue.getVenueAccessibilities() == null ? List.of() : venue.getVenueAccessibilities();
        List<String> requiredFacilities = event.getRequiredFacilities() == null ? List.of() : event.getRequiredFacilities();
        List<String> offeredFacilities = venue.getVenueFacilities() == null ? List.of() : venue.getVenueFacilities();
        return new VenueSuitability(
                event.getExpectedAttendance() == null ? 0 : event.getExpectedAttendance(),
                venue.getVenueCapacity() == null ? 0 : venue.getVenueCapacity(),
                required.stream().filter(need -> !offered.contains(need)).toList(),
                requiredFacilities.stream().filter(facility -> !offeredFacilities.contains(facility)).toList());
    }

    public boolean capacityOk() {
        return expectedAttendance <= capacity;
    }

    public boolean needsJustification() {
        return !missingAccessibility.isEmpty();
    }

    public boolean facilitiesOk() {
        return missingFacilities.isEmpty();
    }
}
