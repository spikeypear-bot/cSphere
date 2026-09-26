package com.example.connect_sphere.eventrequest.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

@Repository
public interface EventRequestRepository extends JpaRepository<EventRequest, UUID> {

    /** Backs EO15 ("view my event requests and their statuses") and the
     * "view draft requests belonging to their own organisation" part of EO01 —
     * organisation is the interim ownership key (see EventRequest's class doc). */
    List<EventRequest> findByOrganisationOrderByCreatedAtDesc(String organisation);


    /** EventService.confirm() needs to know which Event Organiser to notify —
     * events carry no direct reference back to their originating request's
     * creator, only the reverse link (EventRequest.eventId), set once at
     * approval. At most one request ever approves into a given event, so a
     * single result is safe. */
    Optional<EventRequest> findByEventId(UUID eventId);

    /** Every state change (submit, assign, clarify, resubmit, approve,
     * reject) loads the row with this. SELECT ... FOR UPDATE makes a second
     * concurrent action on the same request wait for the first to commit and
     * then see its result — so a double-clicked Approve creates one Event,
     * not two, and the second click gets "already decided". */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EventRequest r WHERE r.requestId = :id")
    Optional<EventRequest> findForUpdate(@Param("id") UUID id);

    /** EC02 queue: submitted requests nobody has picked up yet. */
    List<EventRequest> findByStatusAndCoordinatorIdIsNullOrderByCreatedAtAsc(EventRequestStatus status);

    /** EC02 queue: this coordinator's requests in one status. */
    List<EventRequest> findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(UUID coordinatorId, EventRequestStatus status);
}
