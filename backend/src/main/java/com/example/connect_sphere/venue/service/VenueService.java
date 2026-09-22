package com.example.connect_sphere.venue.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.common.enums.AccessibilityFeature;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.UpdateVenueDto;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;

@Service
public class VenueService {
    private final VenueRepository repository;
    private final VenueMapper mapper;

    public VenueService(VenueRepository repository, VenueMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Creates a catalogue record immediately; no booking/approval workflow. */
    @Transactional
    @PreAuthorize("hasAnyRole('EC','VS')")
    public VenueDto createVenue(CreateVenueDto input) {
        validate(input);
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(input.venueAddress().strip());
        venue.setVenueCapacity(input.venueCapacity());
        venue.setSupportedLayouts(new ArrayList<>(input.supportedLayouts().stream()
                .map(Enum::name).toList()));
        venue.setOperatingInformation(input.operatingInformation().strip());
        venue.setVenueAccessibilities(labels(input.venueAccessibilities()));
        venue.setVenueFacilities(labels(input.venueFacilities()));
        venue.setAdditionalInformation(input.additionalInformation() == null
                ? null : input.additionalInformation().strip());
        return mapper.toDto(repository.save(venue));
    }

    /** Updates catalogue characteristics without accessing bookings. */
    @Transactional
    public VenueDto updateVenue(UUID id, UpdateVenueDto input) {
        Venue venue = repository.findById(id).orElseThrow(() -> new VenueNotFoundException(id));
        if (input == null) throw new InvalidVenueException(List.of("venue details are required"));
        VenueDto current = mapper.toDto(venue);
        CreateVenueDto merged = new CreateVenueDto(current.venueAddress(),
                input.getVenueCapacity() == null ? current.venueCapacity() : input.getVenueCapacity(),
                input.getSupportedLayouts() == null ? current.supportedLayouts() : input.getSupportedLayouts(),
                input.getOperatingInformation() == null ? current.operatingInformation() : input.getOperatingInformation(),
                current.additionalInformation(),
                input.getVenueAccessibilities() == null ? current.venueAccessibilities() : input.getVenueAccessibilities(),
                input.getVenueFacilities() == null ? current.venueFacilities() : input.getVenueFacilities());
        validate(merged);
        if (input.getVenueCapacity() != null) venue.setVenueCapacity(merged.venueCapacity());
        if (input.getSupportedLayouts() != null) venue.setSupportedLayouts(labels(merged.supportedLayouts()));
        if (input.getVenueAccessibilities() != null) venue.setVenueAccessibilities(labels(merged.venueAccessibilities()));
        if (input.getVenueFacilities() != null) venue.setVenueFacilities(labels(merged.venueFacilities()));
        if (input.getOperatingInformation() != null) venue.setOperatingInformation(merged.operatingInformation().strip());
        if (input.getAdditionalInformation() != null) venue.setAdditionalInformation(input.getAdditionalInformation().strip());
        return mapper.toDto(repository.save(venue));
    }

    private static void validate(CreateVenueDto input) {
        if (input == null) {
            throw new InvalidVenueException(List.of("venue details are required"));
        }
        List<String> errors = new ArrayList<>();
        if (input.venueAddress() == null || input.venueAddress().isBlank()) {
            errors.add("venueAddress is required");
        } else if (input.venueAddress().strip().length() > 500) {
            errors.add("venueAddress must be at most 500 characters");
        }
        if (input.venueCapacity() == null || input.venueCapacity() < 1 || input.venueCapacity() > 50000) {
            errors.add("venueCapacity must be between 1 and 50000");
        }
        if (input.supportedLayouts() == null || input.supportedLayouts().isEmpty()) {
            errors.add("supportedLayouts must contain at least one layout");
        } else {
            if (input.supportedLayouts().stream().anyMatch(layout -> layout == null)) {
                errors.add("supportedLayouts must not contain null entries");
            }
            if (new HashSet<>(input.supportedLayouts()).size() != input.supportedLayouts().size()) {
                errors.add("supportedLayouts must not contain duplicates");
            }
        }
        if (input.operatingInformation() == null || input.operatingInformation().isBlank()) {
            errors.add("operatingInformation is required");
        }
        validateSelections("venueAccessibilities", input.venueAccessibilities(), errors);
        validateSelections("venueFacilities", input.venueFacilities(), errors);
        if (input.venueAccessibilities() != null
                && input.venueAccessibilities().contains(AccessibilityFeature.none)
                && input.venueAccessibilities().size() > 1) {
            errors.add("venueAccessibilities: none cannot be combined with other selections");
        }
        if (!errors.isEmpty()) {
            throw new InvalidVenueException(errors);
        }
    }

    private static List<String> labels(List<? extends Enum<?>> values) {
        return values == null ? new ArrayList<>()
                : new ArrayList<>(values.stream().map(Enum::name).toList());
    }

    private static void validateSelections(String field, List<?> values, List<String> errors) {
        if (values == null) return;
        if (values.stream().anyMatch(value -> value == null)) {
            errors.add(field + " must not contain null entries");
        }
        if (new HashSet<>(values).size() != values.size()) {
            errors.add(field + " must not contain duplicates");
        }
    }

    @Transactional(readOnly = true)
    public List<VenueDto> list() {
        return repository.findAll(Sort.by("venueAddress", "venueId")).stream()
                .map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public VenueDto get(UUID id) {
        return mapper.toDto(repository.findById(id)
                .orElseThrow(() -> new VenueNotFoundException(id)));
    }
}
