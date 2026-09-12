package com.example.connect_sphere.eventrequest.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.eventrequest.entity.EventRequest;

@Repository
public interface EventRequestRepository extends JpaRepository<EventRequest, UUID> {

    /** Backs EO15 ("view my event requests and their statuses") and the
     * "view draft requests belonging to their own organisation" part of EO01 —
     * organisation is the interim ownership key (see EventRequest's class doc). */
    List<EventRequest> findByOrganisationOrderByCreatedAtDesc(String organisation);
}
