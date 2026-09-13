package com.example.connect_sphere.venue.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.dto.CreateVenueDto;
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
    public VenueDto createVenue(CreateVenueDto input) {
        validate(input);
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(input.venueAddress().strip());
        venue.setVenueCapacity(input.venueCapacity());
        venue.setSupportedLayouts(new ArrayList<>(input.supportedLayouts().stream()
                .map(Enum::name).toList()));
        venue.setOperatingInformation(input.operatingInformation().strip());
        venue.setAdditionalInformation(input.additionalInformation() == null
                ? null : input.additionalInformation().strip());
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
        if (!errors.isEmpty()) {
            throw new InvalidVenueException(errors);
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
