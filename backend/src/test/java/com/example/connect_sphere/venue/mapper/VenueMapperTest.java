package com.example.connect_sphere.venue.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.entity.VenueLayout;

class VenueMapperTest {
    private final VenueMapper mapper = Mappers.getMapper(VenueMapper.class);

    @Test
    void mapsStoredLayoutStringsToApiEnumsWithOneSharedCapacity() {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress("Example venue");
        venue.setVenueCapacity(50000);
        venue.setSupportedLayouts(List.of("theatre", "classroom"));
        venue.setOperatingInformation("Mon-Fri 09:00-18:00");

        var dto = mapper.toDto(venue);

        assertEquals(venue.getVenueId(), dto.venueId());
        assertEquals(venue.getVenueAddress(), dto.venueAddress());
        assertEquals(50000, dto.venueCapacity());
        assertEquals(List.of(VenueLayout.theatre, VenueLayout.classroom), dto.supportedLayouts());
        assertEquals(venue.getOperatingInformation(), dto.operatingInformation());
        assertNull(dto.additionalInformation());
    }
}
