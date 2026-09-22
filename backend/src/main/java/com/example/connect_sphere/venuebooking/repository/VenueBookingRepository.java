package com.example.connect_sphere.venuebooking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;
import com.example.connect_sphere.venuebooking.entity.VenueBooking;

/** Intentionally exposes reads only. Fetch both sides together to avoid N+1 queries. */
public interface VenueBookingRepository extends Repository<VenueBooking, UUID> {
    @EntityGraph(attributePaths = {"venue", "event"})
    Optional<VenueBooking> findById(UUID id);

    @EntityGraph(attributePaths = {"venue", "event"})
    List<VenueBooking> findByVenueVenueIdOrderByEventStartDatetimeAscBookingIdAsc(UUID venueId);
}
