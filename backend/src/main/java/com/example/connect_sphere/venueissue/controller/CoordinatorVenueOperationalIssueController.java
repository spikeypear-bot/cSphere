package com.example.connect_sphere.venueissue.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.venueissue.dto.CoordinatorVenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.service.VenueOperationalIssueService;

/** EC: operational issues affecting venues with the coordinator's bookings. */
@RestController
@RequestMapping("/api/coordinator/operational-issues")
public class CoordinatorVenueOperationalIssueController {
    private final VenueOperationalIssueService service;

    public CoordinatorVenueOperationalIssueController(VenueOperationalIssueService service) {
        this.service = service;
    }

    @GetMapping
    public List<CoordinatorVenueOperationalIssueDto> list(
            @AuthenticationPrincipal Jwt jwt) {
        return service.listForCoordinator(UUID.fromString(jwt.getSubject()));
    }
}
