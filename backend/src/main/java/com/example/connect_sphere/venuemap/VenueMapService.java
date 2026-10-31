package com.example.connect_sphere.venuemap;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import static com.example.connect_sphere.venuemap.VenueMapDto.*;

@Service
@Transactional(readOnly = true)
public class VenueMapService {
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final VenueMapRepository map;
    private final VenueRepository venues;
    public VenueMapService(VenueMapRepository map, VenueRepository venues) { this.map=map; this.venues=venues; }

    public List<Activity> activity(LocalDate date) {
        return map.activity(OffsetDateTime.now(), start(date), start(date == null ? null : date.plusDays(1)));
    }
    public List<Booking> bookings(UUID venueId, LocalDate date, boolean includePast) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return map.bookings(venueId, OffsetDateTime.now(), start(date),
                start(date == null ? null : date.plusDays(1)), includePast);
    }
    private OffsetDateTime start(LocalDate date) { return date == null ? null : date.atStartOfDay(SINGAPORE).toOffsetDateTime(); }
}
