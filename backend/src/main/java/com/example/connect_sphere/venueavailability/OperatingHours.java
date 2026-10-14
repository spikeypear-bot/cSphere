package com.example.connect_sphere.venueavailability;

import java.time.LocalTime;
import java.time.ZoneId;

/** Venue opening hours use Singapore time and the entire occupied period. */
public final class OperatingHours {
    private OperatingHours() {}

    public static boolean covers(int day, LocalTime opens, LocalTime closes, EffectivePeriod period) {
        var start = period.start().atZoneSameInstant(ZoneId.of("Asia/Singapore"));
        var end = period.end().atZoneSameInstant(ZoneId.of("Asia/Singapore"));
        return start.toLocalDate().equals(end.toLocalDate()) && day == start.getDayOfWeek().getValue()
                && !start.toLocalTime().isBefore(opens) && !end.toLocalTime().isAfter(closes);
    }
}
