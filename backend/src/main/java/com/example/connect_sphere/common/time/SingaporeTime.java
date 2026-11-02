package com.example.connect_sphere.common.time;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Times are stored and compared in UTC; these turn them into what a person in
 * Singapore reads. Use these instead of writing the zone or pattern again.
 */
public final class SingaporeTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

    /** "3 Mar 2027, 9:00 AM" in Singapore time, for notifications and messages. */
    public static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZONE);

    private SingaporeTime() {
    }
}
