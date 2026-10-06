package com.example.connect_sphere.venue.repository;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.venue.entity.Venue;

@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {
    /** Serialises approvals for this venue, including different event rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Venue v WHERE v.venueId = :id")
    Optional<Venue> findForUpdate(@Param("id") UUID id);

}
