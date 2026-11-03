package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.venue.entity.Venue;

@Tag("unit")
@Tag("EC03")
@Tag("VS17")
class VenueSuitabilityTest {

    @Test
    @DisplayName("EC03 VS17 (boundary): an event with no attendance or needs recorded fits a venue that offers nothing")
    void nothingRecordedFits() {
        Venue venue = new Venue();
        venue.setVenueCapacity(1);
        VenueSuitability fit = VenueSuitability.of(new Event(), venue);

        assertThat(fit.expectedAttendance()).isZero();
        assertThat(fit.capacity()).isEqualTo(1);
        assertThat(fit.missingAccessibility()).isEmpty();
        assertThat(fit.missingFacilities()).isEmpty();
        assertThat(fit.capacityOk()).isTrue();
        assertThat(fit.facilitiesOk()).isTrue();
        assertThat(fit.needsJustification()).isFalse();
    }

    @Test
    @DisplayName("EC03 VS17 (boundary): a missing need or facility with no known label is shown by its stored code")
    void unknownCodesAreShownAsStored() {
        Event event = new Event();
        event.setAccessibilityNeeds(List.of("hearing_loop", "elevators"));
        event.setRequiredFacilities(List.of("ice_rink"));

        Venue venue = new Venue();
        venue.setVenueCapacity(1);
        VenueSuitability fit = VenueSuitability.of(event, venue);

        assertThat(fit.missingAccessibilityLabels()).isEqualTo("hearing_loop, Elevators");
        assertThat(fit.missingFacilityLabels()).isEqualTo("ice_rink");
    }
}
