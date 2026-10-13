package com.example.connect_sphere.venuebooking.dto;

import java.util.List;

public record VenueBookingConflictResponse(String message, List<VenueBookingConflictDto> conflicts,
        List<VenueUnavailabilityConflictDto> unavailablePeriods) {}
