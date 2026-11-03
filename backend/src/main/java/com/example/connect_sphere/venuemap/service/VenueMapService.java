package com.example.connect_sphere.venuemap.service;

import java.time.Clock;
import com.example.connect_sphere.common.time.SingaporeTime;
import com.example.connect_sphere.venuemap.repository.VenueMapRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import static com.example.connect_sphere.venuemap.dto.VenueMapDto.*;

@Service
@Transactional(readOnly = true)
public class VenueMapService {
    private static final ZoneId SINGAPORE = SingaporeTime.ZONE;
    private final VenueMapRepository map;
    private final VenueRepository venues;
    private final Clock clock;

    public VenueMapService(VenueMapRepository map, VenueRepository venues, Clock clock) {
        this.clock = clock; this.map=map; this.venues=venues; }

    public List<Activity> activity(LocalDate date) {
        return map.activity(OffsetDateTime.now(clock), start(date), start(date == null ? null : date.plusDays(1)));
    }
    public List<Booking> bookings(UUID venueId, LocalDate date, boolean includePast) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return map.bookings(venueId, OffsetDateTime.now(clock), start(date),
                start(date == null ? null : date.plusDays(1)), includePast);
    }
    private OffsetDateTime start(LocalDate date) { return date == null ? null : date.atStartOfDay(SINGAPORE).toOffsetDateTime(); }
}
