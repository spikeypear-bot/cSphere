package com.example.connect_sphere.venueissue.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.venueissue.dto.VenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.service.VenueOperationalIssueService;

/** VS13: Venue Staff operational issue reporting and read-back. */
@RestController
@RequestMapping("/api/venues/{venueId}/operational-issues")
public class VenueOperationalIssueController {
    private final VenueOperationalIssueService service;

    public VenueOperationalIssueController(VenueOperationalIssueService service) {
        this.service = service;
    }

    @GetMapping
    public List<VenueOperationalIssueDto> list(@PathVariable UUID venueId) {
        return service.list(venueId);
    }

    @PostMapping
    public ResponseEntity<VenueOperationalIssueDto> create(
            @PathVariable UUID venueId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateVenueOperationalIssueRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        VenueOperationalIssueDto saved = service.create(venueId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/venues/" + venueId + "/operational-issues/" + saved.issueId()))
                .body(saved);
    }
}
