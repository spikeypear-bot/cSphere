package com.example.connect_sphere.venue.dto;

import java.util.List;

import com.example.connect_sphere.venue.entity.VenueLayout;

/**
 * Venue Staff's catalogue form input, not a booking or approval request.
 * The service validates required fields, capacity 1-50000 and distinct layouts.
 * The frontend will initialise capacity to 50; omission is not a backend default.
 */
public record CreateVenueDto(
        String venueAddress,
        Integer venueCapacity,
        List<VenueLayout> supportedLayouts,
        String operatingInformation,
        String additionalInformation) {
}
