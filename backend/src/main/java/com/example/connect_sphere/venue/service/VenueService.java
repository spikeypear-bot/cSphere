package com.example.connect_sphere.venue.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.example.connect_sphere.common.enums.AccessibilityFeature;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.UpdateVenueDto;
import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.repository.VenueOperatingHourRepository;
import com.example.connect_sphere.venue.entity.VenueOperatingHour;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;

@Service
public class VenueService {
    @Autowired(required=false) private com.example.connect_sphere.changerequest.BookingAssessmentService assessments;
    private final VenueRepository repository;
    private final VenueMapper mapper;
    private final VenueOperatingHourRepository hours;

    @Autowired
    public VenueService(VenueRepository repository, VenueMapper mapper, VenueOperatingHourRepository hours) {
        this.repository = repository;
        this.mapper = mapper;
        this.hours = hours;
    }

    public VenueService(VenueRepository repository, VenueMapper mapper) {
        this(repository, mapper, null);
    }

    /** Creates a catalogue record immediately; no booking/approval workflow. */
    @Transactional
    public VenueDto createVenue(CreateVenueDto input) {
        validate(input);
        if (input.operatingHours() == null || input.operatingHours().isEmpty()) throw new InvalidVenueException(List.of("operatingHours must contain at least one operating day"));
        Venue venue = new Venue();
        venue.setSetupMinutes(com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(input.setupMinutes(), 0));
        venue.setTurnaroundMinutes(com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(input.turnaroundMinutes(), 0));
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(input.venueAddress().strip());
        venue.setVenueName(input.venueName() == null || input.venueName().isBlank()
                ? input.venueAddress().strip() : input.venueName().strip());
        venue.setVenueCapacity(input.venueCapacity());
        venue.setSupportedLayouts(new ArrayList<>(input.supportedLayouts().stream()
                .map(Enum::name).toList()));
        venue.setOperatingInformation(summarizeHours(input.operatingHours()));
        venue.setVenueAccessibilities(labels(input.venueAccessibilities()));
        venue.setVenueFacilities(labels(input.venueFacilities()));
        venue.setAdditionalInformation(input.additionalInformation() == null
                ? null : input.additionalInformation().strip());
        Venue saved = repository.save(venue);
        saveHours(saved.getVenueId(), input.operatingHours());
        return toDto(saved);
    }

    /** Updates current catalogue facts and reassesses affected bookings atomically. */
    @Transactional
    public VenueDto updateVenue(UUID id, UpdateVenueDto input) {
        var lockedEvents = assessments == null ? java.util.Set.<UUID>of() : assessments.lockVenueEvents(id);
        Venue venue = repository.findForUpdate(id).orElseThrow(() -> new VenueNotFoundException(id));
        if (input == null) throw new InvalidVenueException(List.of("venue details are required"));
        String previousVenue = assessments == null ? null : assessments.venueSnapshot(id);
        VenueDto current = toDto(venue);
        boolean layoutChanged = input.getSupportedLayouts()!=null && !new HashSet<>(input.getSupportedLayouts()).equals(new HashSet<>(current.supportedLayouts()));
        CreateVenueDto merged = new CreateVenueDto(current.venueAddress(),
                input.getVenueCapacity() == null ? current.venueCapacity() : input.getVenueCapacity(),
                input.getSupportedLayouts() == null ? current.supportedLayouts() : input.getSupportedLayouts(),
                current.operatingInformation(),
                current.additionalInformation(),
                input.getVenueAccessibilities() == null ? current.venueAccessibilities() : input.getVenueAccessibilities(),
                input.getVenueFacilities() == null ? current.venueFacilities() : input.getVenueFacilities(),
                input.getOperatingHours() == null ? current.operatingHours() : input.getOperatingHours(),
                null, null, input.getVenueName() == null ? current.venueName() : input.getVenueName());
        validate(merged);
        if (input.getOperatingHours() != null && input.getOperatingHours().isEmpty()) throw new InvalidVenueException(List.of("operatingHours must contain at least one operating day"));
        boolean characteristicsChanged = !java.util.Objects.equals(current.venueCapacity(),merged.venueCapacity())
                || layoutChanged
                || !new HashSet<>(current.venueAccessibilities()).equals(new HashSet<>(merged.venueAccessibilities()))
                || !new HashSet<>(current.venueFacilities()).equals(new HashSet<>(merged.venueFacilities()))
                || input.getOperatingHours()!=null && !new HashSet<>(current.operatingHours()).equals(new HashSet<>(input.getOperatingHours()));
        venue.setSetupMinutes(com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(input.getSetupMinutes(), venue.getSetupMinutes()));
        venue.setTurnaroundMinutes(com.example.connect_sphere.venueavailability.EffectivePeriod.minutes(input.getTurnaroundMinutes(), venue.getTurnaroundMinutes()));
        if (input.getVenueCapacity() != null) venue.setVenueCapacity(merged.venueCapacity());
        if (input.getSupportedLayouts() != null) venue.setSupportedLayouts(labels(merged.supportedLayouts()));
        if (input.getVenueAccessibilities() != null) venue.setVenueAccessibilities(labels(merged.venueAccessibilities()));
        if (input.getVenueFacilities() != null) venue.setVenueFacilities(labels(merged.venueFacilities()));
        if (input.getOperatingHours() != null) venue.setOperatingInformation(summarizeHours(merged.operatingHours()));
        if (input.getVenueName() != null) venue.setVenueName(input.getVenueName().strip());
        if (input.getAdditionalInformation() != null) venue.setAdditionalInformation(input.getAdditionalInformation().strip());
        Venue saved = repository.save(venue);
        if (input.getOperatingHours() != null) saveHours(id, input.getOperatingHours());
        if (assessments != null && characteristicsChanged) {
            repository.flush();
            var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            UUID actor=authentication!=null && authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt ? UUID.fromString(jwt.getSubject()) : null;
            assessments.venueChanged(id,actor,layoutChanged,previousVenue,lockedEvents);
        }
        return toDto(saved);
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
        if (input.venueName() != null && (input.venueName().isBlank() || input.venueName().strip().length() > 200)) {
            errors.add("venueName must contain 1 to 200 characters");
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
        validateSelections("venueAccessibilities", input.venueAccessibilities(), errors);
        validateSelections("venueFacilities", input.venueFacilities(), errors);
        validateHours(input.operatingHours(), errors);
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
                .map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public VenueDto get(UUID id) {
        return toDto(repository.findById(id).orElseThrow(() -> new VenueNotFoundException(id)));
    }

    private VenueDto toDto(Venue venue) {
        List<VenueOperatingHourDto> values = hours == null ? List.of() : hours.findByVenueIdOrderByDayOfWeekAscOpenTimeAsc(venue.getVenueId())
                .stream().map(h -> new VenueOperatingHourDto(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime())).toList();
        VenueDto base = mapper.toDto(venue);
        return new VenueDto(base.venueId(), base.venueAddress(), base.venueCapacity(), base.supportedLayouts(),
                VenueHoursService.summary(values), base.additionalInformation(), base.venueAccessibilities(),
                base.venueFacilities(), values, venue.getSetupMinutes(), venue.getTurnaroundMinutes(), venue.getVenueName());
    }

    private static String summarizeHours(List<VenueOperatingHourDto> values) {
        return VenueHoursService.summary(values);
    }

    private void saveHours(UUID venueId, List<VenueOperatingHourDto> values) {
        if (hours == null) return;
        hours.deleteByVenueId(venueId);
        hours.flush(); // Delete old intervals before inserting replacements with the same unique day.
        if (values != null) hours.saveAll(values.stream()
                .map(h -> new VenueOperatingHour(venueId, h.dayOfWeek(), h.openTime(), h.closeTime())).toList());
    }

    private static void validateHours(List<VenueOperatingHourDto> values, List<String> errors) {
        if (values == null) return;
        if (values.stream().anyMatch(h -> h == null || h.dayOfWeek() < 1 || h.dayOfWeek() > 7
                || h.openTime() == null || h.closeTime() == null || !h.closeTime().isAfter(h.openTime()))) {
            errors.add("operatingHours must contain valid day numbers and close times after open times");
        }
        if (values.stream().filter(java.util.Objects::nonNull).map(VenueOperatingHourDto::dayOfWeek).distinct().count()
                != values.stream().filter(java.util.Objects::nonNull).count()) {
            errors.add("operatingHours must contain at most one interval per day");
        }
    }
}
