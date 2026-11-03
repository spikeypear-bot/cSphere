package com.example.connect_sphere.venuebooking.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;

/** A room's own requirements on a multi-venue booking, checked before anything is saved. */
@Tag("unit")
@Tag("EC03")
class BookingRequirementsTest {

    private static void refused(BookingRequirements requirements, String message) {
        assertThatThrownBy(requirements::validate).isInstanceOf(InvalidVenueBookingException.class).hasMessage(message);
    }

    @Test
    @DisplayName("EC03 (boundary): a room needs at least 1 attendee; 1 is accepted")
    void attendanceAtLeastOne() {
        refused(new BookingRequirements(0, null, null, null), "Booking attendance must be at least 1.");
        assertThatCode(() -> new BookingRequirements(1, null, null, null).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC03 (negative): unknown, missing or repeated facilities and accessibility needs are refused")
    void facilitiesAndNeedsMustBeValidAndDistinct() {
        for (List<String> invalid : List.of(List.of("teleporter"), Arrays.asList("stage", null), List.of("stage", "stage")))
            refused(new BookingRequirements(null, invalid, null, null), "Select valid, distinct booking facilities.");
        refused(new BookingRequirements(null, null, List.of("elevators", "elevators"), null),
                "Select valid, distinct booking accessibility needs.");
        assertThatCode(() -> new BookingRequirements(null, List.of("stage", "projection"), List.of("elevators"), null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC03 (negative): no accessibility needs cannot be combined with a need; alone it is accepted")
    void noneStandsAlone() {
        refused(new BookingRequirements(null, null, List.of("none", "elevators"), null),
                "None cannot be combined with other accessibility needs.");
        assertThatCode(() -> new BookingRequirements(null, null, List.of("none"), null).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC03 (boundary): a room's requirements may be 2000 characters, not 2001")
    void requirementsAtMost2000Characters() {
        refused(new BookingRequirements(null, null, null, "x".repeat(2001)), "Booking requirements must be 2000 characters or fewer.");
        assertThatCode(() -> new BookingRequirements(null, null, null, "x".repeat(2000)).validate()).doesNotThrowAnyException();
    }
}
