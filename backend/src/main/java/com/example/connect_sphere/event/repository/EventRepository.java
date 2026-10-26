package com.example.connect_sphere.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    /** Serialises booking and lifecycle mutations for the same event. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.eventId = :id")
    Optional<Event> findForUpdate(@Param("id") UUID id);

    /** EC09: one coordinator's events in the given statuses, soonest first. */
    List<Event> findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(
            UUID coordinatorId, Collection<EventStatus> statuses);

    /** Safety Officer queues; both planning and confirmed events are ordered soonest first. */
    List<Event> findByStatusOrderByStartDatetimeAsc(EventStatus status);

    /** SO-C1 + EC08: every event not yet confirmed, soonest first. */
    List<Event> findByStatusInOrderByStartDatetimeAsc(Collection<EventStatus> statuses);
}
