package com.example.connect_sphere.event.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.event.entity.Event;

import jakarta.persistence.LockModeType;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    /** EC03 takes this lock before checking "one active booking request per
     * event" and inserting one. Two coordinators (or one double-click)
     * submitting for the same event are serialised on the event row, so the
     * second sees the first's booking and is refused. That makes the rule
     * race-safe without a unique index, which VS02's own fixtures (several
     * bookings per event) would violate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.eventId = :id")
    Optional<Event> findForUpdate(@Param("id") UUID id);
}
