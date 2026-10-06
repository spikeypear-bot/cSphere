package com.example.connect_sphere.venuebooking.dto;

import java.util.UUID;

public record RejectVenueBookingRequest(String reason, UUID alternativeVenueId, String alternativeArrangement) {}
