package com.example.connect_sphere.venue.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

@Service
public class VenueSearchService {
    private final VenueRepository venues;
    private final VenueBookingRecordRepository bookings;
    private final VenueMapper mapper;

    public VenueSearchService(
            VenueRepository venues,
            VenueBookingRecordRepository bookings,
            VenueMapper mapper) {
        this.venues = venues;
        this.bookings = bookings;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<VenueDto> search(
            String rawStart,
            String rawEnd,
            String rawCapacity,
            List<String> rawFacilities) {
        OffsetDateTime start = parseDateTime(rawStart, "Start date and time");
        OffsetDateTime end = parseDateTime(rawEnd, "End date and time");
        if (!start.isBefore(end)) {
            throw new InvalidVenueSearchException("End date and time must be after the start date and time.");
        }

        Integer capacity = parseCapacity(rawCapacity);
        List<String> facilities = parseFacilities(rawFacilities);
        Set<UUID> unavailable = new HashSet<>(bookings.findVenueIdsWithOverlappingBookings(
                VenueBookingStatus.confirmed, start, end));

        return venues.findAll(Sort.by("venueAddress", "venueId")).stream()
                .filter(venue -> !unavailable.contains(venue.getVenueId()))
                .filter(venue -> capacity == null || venue.getVenueCapacity() >= capacity)
                .filter(venue -> hasFacilities(venue, facilities))
                .map(mapper::toDto)
                .toList();
    }

    private static OffsetDateTime parseDateTime(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidVenueSearchException(label + " is required.");
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new InvalidVenueSearchException(label + " must be a valid ISO-8601 date and time.");
        }
    }

    private static Integer parseCapacity(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int capacity = Integer.parseInt(raw);
            if (capacity < 1) {
                throw new InvalidVenueSearchException("Required capacity must be a whole number greater than 0.");
            }
            return capacity;
        } catch (NumberFormatException ex) {
            throw new InvalidVenueSearchException("Required capacity must be a whole number greater than 0.");
        }
    }

    private static List<String> parseFacilities(List<String> rawFacilities) {
        if (rawFacilities == null || rawFacilities.isEmpty()) return List.of();
        for (String facility : rawFacilities) {
            if (facility == null || facility.isBlank()) {
                throw new InvalidVenueSearchException("Required facilities must be valid facility selections.");
            }
            try {
                Facility.valueOf(facility);
            } catch (IllegalArgumentException ex) {
                throw new InvalidVenueSearchException("Required facilities must be valid facility selections.");
            }
        }
        return rawFacilities.stream().distinct().toList();
    }

    private static boolean hasFacilities(Venue venue, List<String> requiredFacilities) {
        List<String> offered = venue.getVenueFacilities() == null ? List.of() : venue.getVenueFacilities();
        return offered.containsAll(requiredFacilities);
    }
}
