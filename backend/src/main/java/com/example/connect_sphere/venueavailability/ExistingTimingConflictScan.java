package com.example.connect_sphere.venueavailability;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.example.connect_sphere.venue.repository.VenueRepository;

/** A deduplicated startup scan also identifies commitments predating this feature. */
@Component
public class ExistingTimingConflictScan {
    private final VenueRepository venues;
    private final TimingConflictService conflicts;
    public ExistingTimingConflictScan(VenueRepository venues, TimingConflictService conflicts) {
        this.venues = venues; this.conflicts = conflicts;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        for (var venue : venues.findAll()) conflicts.scan(venue.getVenueId());
    }
}
