package com.example.connect_sphere.venue.dto;

import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;

import com.example.connect_sphere.venue.entity.VenueLayout;

/** Saved venue catalogue details, including the application-generated ID. */
public record VenueDto(
        UUID venueId,
        String venueAddress,
        Integer venueCapacity,
        List<VenueLayout> supportedLayouts,
        String operatingInformation,
        String additionalInformation,
        List<AccessibilityFeature> venueAccessibilities,
        List<Facility> venueFacilities,
        List<VenueOperatingHourDto> operatingHours, int setupMinutes, int turnaroundMinutes, String venueName,
        Double latitude, Double longitude) {
    public VenueDto(UUID venueId, String venueAddress, Integer venueCapacity,
            List<VenueLayout> supportedLayouts, String operatingInformation, String additionalInformation,
            List<AccessibilityFeature> venueAccessibilities, List<Facility> venueFacilities,
            List<VenueOperatingHourDto> operatingHours, int setupMinutes, int turnaroundMinutes, String venueName) {
        this(venueId, venueAddress, venueCapacity, supportedLayouts, operatingInformation,
                additionalInformation, venueAccessibilities, venueFacilities, operatingHours,
                setupMinutes, turnaroundMinutes, venueName, null, null);
    }
    public VenueDto(UUID venueId, String venueAddress, Integer venueCapacity,
            List<VenueLayout> supportedLayouts, String operatingInformation,
            String additionalInformation, List<AccessibilityFeature> venueAccessibilities,
            List<Facility> venueFacilities, List<VenueOperatingHourDto> operatingHours) {
        this(venueId, venueAddress, venueCapacity, supportedLayouts, operatingInformation,
                additionalInformation, venueAccessibilities, venueFacilities, operatingHours, 0, 0, null);
    }
    public VenueDto(UUID venueId, String venueAddress, Integer venueCapacity,
            List<VenueLayout> supportedLayouts, String operatingInformation,
            String additionalInformation, List<AccessibilityFeature> venueAccessibilities,
            List<Facility> venueFacilities) {
        this(venueId, venueAddress, venueCapacity, supportedLayouts, operatingInformation,
                additionalInformation, venueAccessibilities, venueFacilities, List.of(), 0, 0, null);
    }
    public VenueDto(UUID venueId, String venueAddress, Integer venueCapacity,
            List<VenueLayout> supportedLayouts, String operatingInformation, String additionalInformation,
            List<AccessibilityFeature> venueAccessibilities, List<Facility> venueFacilities,
            List<VenueOperatingHourDto> operatingHours, int setupMinutes, int turnaroundMinutes) {
        this(venueId, venueAddress, venueCapacity, supportedLayouts, operatingInformation,
                additionalInformation, venueAccessibilities, venueFacilities, operatingHours, setupMinutes, turnaroundMinutes, null);
    }
}
