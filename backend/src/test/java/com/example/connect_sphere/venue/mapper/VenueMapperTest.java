package com.example.connect_sphere.venue.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.entity.VenueLayout;

class VenueMapperTest {
    private final VenueMapper mapper = Mappers.getMapper(VenueMapper.class);

    @Test
    void mapsEmptySelectionsToEmptyResponseLists() {
        var dto = mapper.toDto(new Venue());
        assertEquals(List.of(), dto.venueAccessibilities());
        assertEquals(List.of(), dto.venueFacilities());
    }

    @Test
    void mapsEveryDatabaseEnumLabel() {
        var venue = new Venue();
        venue.setVenueAccessibilities(java.util.Arrays.stream(AccessibilityFeature.values()).map(Enum::name).toList());
        venue.setVenueFacilities(java.util.Arrays.stream(Facility.values()).map(Enum::name).toList());
        var dto = mapper.toDto(venue);
        assertEquals(List.of(AccessibilityFeature.values()), dto.venueAccessibilities());
        assertEquals(List.of(Facility.values()), dto.venueFacilities());
    }

    @Test
    void mapsStoredLayoutStringsToApiEnumsWithOneSharedCapacity() {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress("Example venue");
        venue.setVenueCapacity(50000);
        venue.setSupportedLayouts(List.of("theatre", "classroom"));
        venue.setOperatingInformation("Mon-Fri 09:00-18:00");
        venue.setVenueAccessibilities(List.of("step_free_access", "elevators"));
        venue.setVenueFacilities(List.of("projection", "stage"));

        var dto = mapper.toDto(venue);

        assertEquals(venue.getVenueId(), dto.venueId());
        assertEquals(venue.getVenueAddress(), dto.venueAddress());
        assertEquals(50000, dto.venueCapacity());
        assertEquals(List.of(VenueLayout.theatre, VenueLayout.classroom), dto.supportedLayouts());
        assertEquals(venue.getOperatingInformation(), dto.operatingInformation());
        assertNull(dto.additionalInformation());
        assertEquals(List.of(AccessibilityFeature.step_free_access, AccessibilityFeature.elevators), dto.venueAccessibilities());
        assertEquals(List.of(Facility.projection, Facility.stage), dto.venueFacilities());
    }
}
