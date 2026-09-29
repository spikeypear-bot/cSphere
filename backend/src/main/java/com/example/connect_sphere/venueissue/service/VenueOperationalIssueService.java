package com.example.connect_sphere.venueissue.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.venueissue.dto.VenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;

@Service
public class VenueOperationalIssueService {
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final VenueOperationalIssueRepository issues;
    private final VenueRepository venues;

    public VenueOperationalIssueService(VenueOperationalIssueRepository issues, VenueRepository venues) {
        this.issues = issues;
        this.venues = venues;
    }

    @Transactional
    public VenueOperationalIssueDto create(
            UUID venueId, UUID userId, CreateVenueOperationalIssueRequest request) {
        if (!venues.existsById(venueId)) {
            throw new VenueNotFoundException(venueId);
        }
        validate(request);
        VenueOperationalIssue issue = new VenueOperationalIssue(
                venueId,
                request.description().strip(),
                request.affectedFrom(),
                request.affectedUntil(),
                userId);
        return VenueOperationalIssueDto.from(issues.save(issue));
    }

    @Transactional(readOnly = true)
    public List<VenueOperationalIssueDto> list(UUID venueId) {
        if (!venues.existsById(venueId)) {
            throw new VenueNotFoundException(venueId);
        }
        return issues.findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId)
                .stream().map(VenueOperationalIssueDto::from).toList();
    }

    private static void validate(CreateVenueOperationalIssueRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()) {
            throw new InvalidVenueOperationalIssueException("Issue description is required.");
        }
        if (request.description().strip().length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidVenueOperationalIssueException(
                    "Issue description must be at most " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        OffsetDateTime from = request.affectedFrom();
        OffsetDateTime until = request.affectedUntil();
        if ((from == null) != (until == null)) {
            throw new InvalidVenueOperationalIssueException(
                    "Affected start and end date/time must be supplied together.");
        }
        if (from != null && !until.isAfter(from)) {
            throw new InvalidVenueOperationalIssueException(
                    "Affected end date/time must be after the start date/time.");
        }
    }
}
