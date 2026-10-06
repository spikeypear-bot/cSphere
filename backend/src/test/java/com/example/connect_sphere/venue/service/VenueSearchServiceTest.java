package com.example.connect_sphere.venue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

class VenueSearchServiceTest {
    private final VenueRepository venues = mock(VenueRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueMapper mapper = mock(VenueMapper.class);
    private final VenueSearchService service = new VenueSearchService(venues, bookings, mapper);

    @BeforeEach
    void setUp() {
        when(bookings.findVenueIdsWithOverlappingBookings(
                eq(VenueBookingStatus.approved), any(), any())).thenReturn(List.of());
    }

    @Test
    void filtersByMinimumCapacityAndEveryRequiredFacility() {
        Venue exactFit = venue(100, "stage", "projection");
        Venue tooSmall = venue(99, "stage", "projection");
        Venue missingFacility = venue(150, "stage");
        when(venues.findAll(any(Sort.class))).thenReturn(List.of(exactFit, tooSmall, missingFacility));
        when(mapper.toDto(exactFit)).thenReturn(dto(exactFit));

        List<VenueDto> results = service.search(
                "2027-03-10T09:00:00+08:00",
                "2027-03-10T12:00:00+08:00",
                "100",
                List.of("stage", "projection"));

        assertThat(results).extracting(VenueDto::venueId).containsExactly(exactFit.getVenueId());
        verify(bookings).findVenueIdsWithOverlappingBookings(
                eq(VenueBookingStatus.approved), any(), any());
    }

    @Test
    void rejectsInvalidDatesAndCapacityBeforeSearching() {
        assertThatThrownBy(() -> service.search(
                "not-a-date", "2027-03-10T12:00:00+08:00", null, List.of()))
                .isInstanceOf(InvalidVenueSearchException.class)
                .hasMessageContaining("valid ISO-8601");
        assertThatThrownBy(() -> service.search(
                "2027-03-10T09:00:00+08:00", "2027-03-10T12:00:00+08:00", "-1", List.of()))
                .isInstanceOf(InvalidVenueSearchException.class)
                .hasMessageContaining("greater than 0");
    }

    private static Venue venue(int capacity, String... facilities) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress("Test venue");
        venue.setVenueCapacity(capacity);
        venue.setSupportedLayouts(List.of(VenueLayout.theatre.name()));
        venue.setVenueFacilities(List.of(facilities));
        venue.setVenueAccessibilities(List.of());
        venue.setOperatingInformation("Daily");
        return venue;
    }

    private static VenueDto dto(Venue venue) {
        return new VenueDto(venue.getVenueId(), venue.getVenueAddress(), venue.getVenueCapacity(),
                List.of(VenueLayout.theatre), venue.getOperatingInformation(), null, List.of(),
                venue.getVenueFacilities().stream().map(Facility::valueOf).toList());
    }
}
