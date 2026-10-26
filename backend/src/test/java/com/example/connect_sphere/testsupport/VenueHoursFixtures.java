package com.example.connect_sphere.testsupport;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;

/** Explicit valid catalogue schedule for fixtures whose journey concerns booking decisions. */
public final class VenueHoursFixtures {
    private VenueHoursFixtures() {}
    public static List<VenueOperatingHourDto> allWeek() {
        return IntStream.rangeClosed(1,7).mapToObj(day -> new VenueOperatingHourDto(day,LocalTime.MIDNIGHT,LocalTime.of(23,59))).toList();
    }
}
