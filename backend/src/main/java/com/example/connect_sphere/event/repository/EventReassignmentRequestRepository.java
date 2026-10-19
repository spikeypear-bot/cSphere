package com.example.connect_sphere.event.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.event.entity.EventReassignmentRequest;
import com.example.connect_sphere.event.entity.EventReassignmentStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface EventReassignmentRequestRepository extends JpaRepository<EventReassignmentRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EventReassignmentRequest r WHERE r.reassignmentRequestId = :id")
    Optional<EventReassignmentRequest> findForUpdate(@Param("id") UUID id);

    List<EventReassignmentRequest> findByStatusOrderByRequestedAtAsc(EventReassignmentStatus status);

    List<EventReassignmentRequest> findByEventIdOrderByRequestedAtDesc(UUID eventId);

    Optional<EventReassignmentRequest> findFirstByEventIdAndRequesterUserIdOrderByRequestedAtDesc(
            UUID eventId, UUID requesterUserId);

    boolean existsByEventIdAndStatus(UUID eventId, EventReassignmentStatus status);
}
