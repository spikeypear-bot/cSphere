package com.example.connect_sphere.event.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * The Event Coordinator/Organiser-facing event read model — see
 * EventRequestService for how an {@link Event} first comes to exist
 * (approval). Confirmation is performed only by ReadinessService after the
 * Safety Officer checklist is complete.
 */
@Service
public class EventService {

    /** EC09: cancelled and completed events need no further planning. */
    private static final List<EventStatus> ACTIVE_STATUSES = List.of(EventStatus.pending, EventStatus.confirmed);

    private final EventRepository repository;
    private final UserRepository userRepository;

    public EventService(
            EventRepository repository,
            UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** EO09 "can view the confirmed event arrangements ... subject to the
     * relevant confirmed-arrangements feature" — scoped to the caller's own
     * organisation, the same not-found-either-way shape as
     * EventRequestService.get(), so a cross-organisation event is
     * indistinguishable from a missing one. */
    @Transactional(readOnly = true)
    public EventDto get(String organisation, UUID eventId) {
        Event event = repository.findById(eventId)
                .filter(e -> organisation.equals(e.getOrganisation()))
                .orElseThrow(() -> new EventNotFoundException(eventId));
        return toDto(event);
    }

    /** Coordinator-side read of the same event, no organisation scoping —
     * mirrors the review queue's own reasoning (Coordinators are internal). */
    @Transactional(readOnly = true)
    public EventDto getForCoordinator(UUID eventId) {
        Event event = repository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        return toDto(event);
    }

    /** EC09: the caller's own active (pending or confirmed) events, across
     * all organisations, soonest first. Read-only. */
    @Transactional(readOnly = true)
    public List<EventDto> assignedEvents(UUID coordinatorId) {
        return repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(coordinatorId, ACTIVE_STATUSES)
                .stream().map(this::toDto).toList();
    }

    private EventDto toDto(Event event) {
        String coordinatorName = null;
        String coordinatorEmail = null;
        if (event.getCoordinatorId() != null) {
            User coordinator = userRepository.findById(event.getCoordinatorId()).orElse(null);
            if (coordinator != null) {
                coordinatorName = coordinator.getUsername();
                coordinatorEmail = coordinator.getEmail();
            }
        }
        return new EventDto(
                event.getEventId(),
                event.getEventName(),
                event.getPurpose(),
                event.getDescription(),
                event.getStartDatetime(),
                event.getEndDatetime(),
                event.getExpectedAttendance(),
                event.getVenueId(),
                event.getAccessibilityNeeds(),
                event.getRegistrationNeeds(),
                event.getOrganisation(),
                event.getVenueRequirements(),
                event.getEquipmentRequirements(),
                event.getStatus().name(),
                coordinatorName,
                coordinatorEmail);
    }
}
