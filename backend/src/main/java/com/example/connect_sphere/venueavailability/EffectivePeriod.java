package com.example.connect_sphere.venueavailability;

import java.time.OffsetDateTime;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Settings;
import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;

/** Operational occupancy; the advertised event timestamps are never changed. */
public record EffectivePeriod(OffsetDateTime start, OffsetDateTime end) {
    public static int minutes(java.math.BigDecimal value, int fallback) {
        if (value == null) return fallback;
        try {
            int result = value.intValueExact();
            validate(new Settings(result, 0));
            return result;
        } catch (ArithmeticException ex) {
            throw new InvalidVenueBookingException("Setup and turnaround must be whole minutes from 0 to 10080.");
        }
    }
    public static EffectivePeriod of(OffsetDateTime eventStart, OffsetDateTime eventEnd, Settings durations) {
        if (eventStart == null || eventEnd == null || !eventEnd.isAfter(eventStart))
            throw new InvalidVenueBookingException("Each venue must have an end date/time later than its start date/time.");
        validate(durations);
        return new EffectivePeriod(eventStart.minusMinutes(durations.setupMinutes()),
                eventEnd.plusMinutes(durations.turnaroundMinutes()));
    }

    public static void validate(Settings durations) {
        if (durations == null || durations.setupMinutes() == null || durations.turnaroundMinutes() == null
                || durations.setupMinutes() < 0 || durations.turnaroundMinutes() < 0
                || durations.setupMinutes() > 10080 || durations.turnaroundMinutes() > 10080) {
            throw new InvalidVenueBookingException("Setup and turnaround must be whole minutes from 0 to 10080.");
        }
    }
}
