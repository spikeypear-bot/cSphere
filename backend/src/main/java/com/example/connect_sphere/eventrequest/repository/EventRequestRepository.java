package com.example.connect_sphere.eventrequest.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

@Repository
public interface EventRequestRepository extends JpaRepository<EventRequest, UUID> {

    /** Backs EO15 ("view my event requests and their statuses") and the
     * "view draft requests belonging to their own organisation" part of EO01 —
     * organisation is the interim ownership key (see EventRequest's class doc). */
    List<EventRequest> findByOrganisationOrderByCreatedAtDesc(String organisation);

    /** The Event Coordinator review queue (EC01/EC02, minimal slice built
     * alongside EO09/EO19 so their notifications have something real to fire
     * from) — every organisation's requests, unlike the Organiser's own
     * organisation-scoped list, since Coordinators are internal staff. Oldest
     * first: the request that has been waiting longest surfaces first. */
    List<EventRequest> findByStatusOrderByCreatedAtAsc(EventRequestStatus status);

    /** EventService.confirm() needs to know which Event Organiser to notify —
     * events carry no direct reference back to their originating request's
     * creator, only the reverse link (EventRequest.eventId), set once at
     * approval. At most one request ever approves into a given event, so a
     * single result is safe. */
    Optional<EventRequest> findByEventId(UUID eventId);
}
