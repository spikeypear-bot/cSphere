package com.example.connect_sphere.safety;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;

@RestController
@RequestMapping("/api/safety")
public class SafetyOfficerController {

    private final EventRepository events;
    private final ReadinessService readiness;
    private final VenueRepository venues;
    private final JourneyStatusResolver statuses;

    public SafetyOfficerController(EventRepository events, ReadinessService readiness, VenueRepository venues,
            JourneyStatusResolver statuses) {
        this.events = events;
        this.readiness = readiness;
        this.venues = venues;
        this.statuses = statuses;
    }

    @GetMapping("/events/confirmed")
    public List<SafetyEventListItemDto> confirmedEvents() {
        return list(EventStatus.confirmed);
    }

    /**
     * Events not yet confirmed: in Planning, awaiting the safety check, sent
     * back for changes, or approved and waiting for the coordinator to
     * confirm (EC08). The status on each row says which.
     */
    @GetMapping("/events/planning")
    public List<SafetyEventListItemDto> planningEvents() {
        return toItems(events.findByStatusInOrderByStartDatetimeAsc(PRE_CONFIRMATION));
    }

    private static final List<EventStatus> PRE_CONFIRMATION = List.copyOf(EventStatus.BEFORE_CONFIRMATION);


    @GetMapping("/events/{eventId}")
    public EventDto getEvent(@PathVariable UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!EventStatus.ACTIVE.contains(event.getStatus())) {
            throw new EventNotFoundException(eventId);
        }
        return toDto(event);
    }

    @GetMapping("/events/{eventId}/readiness")
    public ReadinessDto readiness(@PathVariable UUID eventId) {
        return readiness.forSafetyOfficer(eventId);
    }

    @PatchMapping("/events/{eventId}/readiness/items/{itemKey}")
    public ReadinessDto updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @PathVariable String itemKey, @RequestBody UpdateReadinessItemRequest request) {
        return readiness.updateItem(userIdOf(jwt), eventId, itemKey, request);
    }

    @PostMapping("/events/{eventId}/approval")
    public ReadinessDto review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId,
            @RequestBody SafetyApprovalRequest request) {
        return readiness.review(userIdOf(jwt), eventId, request);
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private List<SafetyEventListItemDto> list(EventStatus status) {
        return toItems(events.findByStatusOrderByStartDatetimeAsc(status));
    }

    private List<SafetyEventListItemDto> toItems(List<Event> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var venueIds = rows.stream().map(Event::getVenueId).filter(java.util.Objects::nonNull).toList();
        var venueById = venues.findAllById(venueIds).stream()
                .collect(java.util.stream.Collectors.toMap(Venue::getVenueId, Venue::getVenueAddress));
        return rows.stream().map(event -> new SafetyEventListItemDto(
                event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                event.getVenueId() == null ? null : venueById.get(event.getVenueId()), event.getStatus().name(),
                statuses.forEvent(event)))
                .toList();
    }

    private static EventDto toDto(Event event) {
        return new EventDto(event.getEventId(), event.getEventName(), event.getPurpose(), event.getDescription(),
                event.getStartDatetime(), event.getEndDatetime(), event.getExpectedAttendance(), event.getVenueId(),
                event.getAccessibilityNeeds(), event.getRequiredFacilities(), event.getRegistrationNeeds(),
                event.getOrganisation(), event.getVenueRequirements(), event.getEquipmentRequirements(),
                event.getStatus().name(), null, null);
    }
}
