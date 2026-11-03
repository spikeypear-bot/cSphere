package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.connect_sphere.venueavailability.service.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

@Tag("unit")
@Tag("EC10")
class ReplacementSourceTest {

    private final AvailabilityService availability = mock(AvailabilityService.class);

    private static VenueBookingRecord booking(VenueBookingStatus status) {
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setStatus(status);
        return booking;
    }

    @ParameterizedTest
    @EnumSource(value = VenueBookingStatus.class, names = {"rejected", "cancelled", "approved", "attention"})
    @DisplayName("EC10 (happy): a rejected, cancelled, approved or flagged booking can be replaced, whatever its venue's availability")
    void settledBookingsCanBeReplaced(VenueBookingStatus status) {
        assertThat(ReplacementSource.eligible(booking(status), availability)).isTrue();
        verifyNoInteractions(availability);
    }

    @Test
    @DisplayName("EC10 (boundary): a pending booking can be replaced only once something now blocks its venue")
    void aPendingBookingNeedsABlockedVenue() {
        VenueBookingRecord pending = booking(VenueBookingStatus.pending);
        when(availability.affected(pending.getBookingId())).thenReturn(false, true);

        assertThat(ReplacementSource.eligible(pending, availability)).isFalse();
        assertThat(ReplacementSource.eligible(pending, availability)).isTrue();
    }

    @Test
    @DisplayName("EC10 (negative): a booking already superseded by its replacement cannot be replaced again")
    void aSupersededBookingCannotBeReplaced() {
        assertThat(ReplacementSource.eligible(booking(VenueBookingStatus.changed), availability)).isFalse();
        verifyNoInteractions(availability);
    }
}
