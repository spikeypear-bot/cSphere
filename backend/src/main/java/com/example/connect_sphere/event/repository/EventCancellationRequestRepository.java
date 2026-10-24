package com.example.connect_sphere.event.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.event.entity.EventCancellationRequest;
import com.example.connect_sphere.event.entity.EventCancellationStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface EventCancellationRequestRepository extends JpaRepository<EventCancellationRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EventCancellationRequest r WHERE r.cancellationRequestId = :id")
    Optional<EventCancellationRequest> findForUpdate(@Param("id") UUID id);

    /** Newest first: the pending one (if any) leads, then earlier decisions. */
    List<EventCancellationRequest> findByEventIdOrderByRequestedAtDesc(UUID eventId);

    boolean existsByEventIdAndStatus(UUID eventId, EventCancellationStatus status);
}
