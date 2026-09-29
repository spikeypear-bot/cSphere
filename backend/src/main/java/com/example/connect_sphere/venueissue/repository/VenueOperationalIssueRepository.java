package com.example.connect_sphere.venueissue.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;

public interface VenueOperationalIssueRepository extends JpaRepository<VenueOperationalIssue, UUID> {

    List<VenueOperationalIssue> findByVenueIdOrderByAffectedFromAscCreatedAtDesc(UUID venueId);
}
