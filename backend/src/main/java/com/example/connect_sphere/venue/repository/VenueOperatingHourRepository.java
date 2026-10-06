package com.example.connect_sphere.venue.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.connect_sphere.venue.entity.VenueOperatingHour;

public interface VenueOperatingHourRepository extends JpaRepository<VenueOperatingHour, UUID> {
    List<VenueOperatingHour> findByVenueIdOrderByDayOfWeekAscOpenTimeAsc(UUID venueId);
    List<VenueOperatingHour> findByVenueIdIn(List<UUID> venueIds);
    void deleteByVenueId(UUID venueId);
}
