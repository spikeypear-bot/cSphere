package com.example.connect_sphere.venueavailability;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Settings;
import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;

@Tag("unit")
@Tag("VS-C2")
class EffectivePeriodTest {
    @Test void calculatesAcrossMidnightAndYearBoundariesWithoutChangingAdvertisedTimes() {
        var start = OffsetDateTime.parse("2027-01-01T00:15:00+08:00");
        var end = OffsetDateTime.parse("2027-01-01T23:45:00+08:00");
        var period = EffectivePeriod.of(start, end, new Settings(30, 60));
        assertThat(period.start()).isEqualTo(OffsetDateTime.parse("2026-12-31T23:45:00+08:00"));
        assertThat(period.end()).isEqualTo(OffsetDateTime.parse("2027-01-02T00:45:00+08:00"));
        assertThat(EffectivePeriod.of(start, end, new Settings(0, 0))).isEqualTo(new EffectivePeriod(start, end));
        assertThat(EffectivePeriod.of(start, end, new Settings(10080, 10080)))
                .isEqualTo(new EffectivePeriod(start.minusDays(7), end.plusDays(7)));
        assertThat(AvailabilityService.overlaps(period.start(), period.end(),
                OffsetDateTime.parse("2027-01-01T16:45:00Z"), OffsetDateTime.parse("2027-01-01T17:00:00Z"))).isFalse();
        assertThat(AvailabilityService.overlaps(period.start(), period.end(),
                OffsetDateTime.parse("2027-01-01T16:44:00Z"), OffsetDateTime.parse("2027-01-01T17:00:00Z"))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "10081,0", "0,10081", ",0", "0,"})
    void rejectsInvalidDurations(Integer setup, Integer turnaround) {
        assertThatThrownBy(() -> EffectivePeriod.validate(new Settings(setup, turnaround)))
                .isInstanceOf(InvalidVenueBookingException.class);
    }

    @Test void rejectsMissingDurationsAndFractionalJsonValues() {
        assertThatThrownBy(() -> EffectivePeriod.validate(null)).isInstanceOf(InvalidVenueBookingException.class);
        assertThatThrownBy(() -> Settings.fromJson(new java.math.BigDecimal("0.5"), java.math.BigDecimal.ZERO))
                .isInstanceOf(InvalidVenueBookingException.class);
    }
}
