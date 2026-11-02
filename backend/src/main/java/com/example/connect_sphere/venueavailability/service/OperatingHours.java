package com.example.connect_sphere.venueavailability.service;

import com.example.connect_sphere.common.time.SingaporeTime;
import com.example.connect_sphere.venueavailability.entity.EffectivePeriod;
import java.time.LocalTime;

/** Venue opening hours use Singapore time and the entire occupied period. */
public final class OperatingHours {
    private OperatingHours() {}

    public static boolean covers(int day, LocalTime opens, LocalTime closes, EffectivePeriod period) {
        var start = period.start().atZoneSameInstant(SingaporeTime.ZONE);
        var end = period.end().atZoneSameInstant(SingaporeTime.ZONE);
        return start.toLocalDate().equals(end.toLocalDate()) && day == start.getDayOfWeek().getValue()
                && !start.toLocalTime().isBefore(opens) && !end.toLocalTime().isAfter(closes);
    }
}
