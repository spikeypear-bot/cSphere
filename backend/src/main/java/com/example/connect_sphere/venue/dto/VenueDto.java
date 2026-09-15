package com.example.connect_sphere.venue.dto;

import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.venue.entity.VenueLayout;

/** Saved venue catalogue details, including the application-generated ID. */
public record VenueDto(
        UUID venueId,
        String venueAddress,
        Integer venueCapacity,
        List<VenueLayout> supportedLayouts,
        String operatingInformation,
        String additionalInformation) {
}
