package com.example.connect_sphere.event.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.dto.CoordinatorEventsDto;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;

/**
 * The Event Coordinator/Organiser-facing event read model — see
 * EventRequestService for how an {@link Event} first comes to exist
 * (approval). Confirmation is performed only by ReadinessService after the
 * Safety Officer checklist is complete.
 */
@Service
public class EventService {

    /** EC09: cancelled and completed events need no further planning. Events
     * in safety review (EC08) are still the coordinator's responsibility. */
    private static final Set<EventStatus> ACTIVE_STATUSES = EventStatus.ACTIVE;

    private final EventRepository repository;
    private final UserRepository userRepository;
    private final EventRequestRepository eventRequestRepository;
    private final NotificationService notificationService;
    private final JourneyStatusResolver statuses;
    private final EventLifecycleService lifecycle;

    public EventService(
            EventRepository repository,
            EventRequestRepository eventRequestRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            JourneyStatusResolver statuses,
            EventLifecycleService lifecycle) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventRequestRepository = eventRequestRepository;
        this.notificationService = notificationService;
        this.statuses = statuses;
        this.lifecycle = lifecycle;
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
    public EventDto getForCoordinator(UUID coordinatorId, UUID eventId) {
        Event event = repository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        // ECL-C7: only the coordinator the Lead assigned may open it.
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(eventId);
        }
        return toDto(event);
    }

    /**
     * ECL-C7: every Event Coordinator with the active events they hold (not
     * cancelled or finished), soonest first, for the Lead's oversight and
     * reassignment. Each event comes with its originating request, which is
     * what the Lead reassigns (ELC-C5). Read-only.
     */
    @Transactional(readOnly = true)
    public List<CoordinatorEventsDto> activeEventsByCoordinator() {
        return userRepository.findByRole(UserRole.ec).stream()
                .sorted(java.util.Comparator.comparing(User::getUsername))
                .map(coordinator -> new CoordinatorEventsDto(coordinator.getUserId(), coordinator.getUsername(),
                        repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(coordinator.getUserId(),
                                        ACTIVE_STATUSES).stream()
                                .filter(event -> !statuses.forEvent(event).isFinal())
                                .map(event -> new CoordinatorEventsDto.HeldEvent(
                                        eventRequestRepository.findByEventId(event.getEventId())
                                                .map(EventRequest::getRequestId).orElse(null),
                                        toDto(event)))
                                .toList()))
                .toList();
    }

    /** ECL-C7: the Event Coordinator Lead may view any event, read-only. */
    @Transactional(readOnly = true)
    public EventDto getForLead(UUID eventId) {
        return toDto(repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId)));
    }

    /** EC09: the caller's own active (pending or confirmed) events, across
     * all organisations, soonest first. Read-only. */
    @Transactional(readOnly = true)
    public List<EventDto> assignedEvents(UUID coordinatorId) {
        return repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(coordinatorId, ACTIVE_STATUSES)
                .stream().map(this::toDto).toList();
    }

    /** EO09 "Confirmed". */
    /**
     * EC08: confirming now requires the Safety Officer's approval and every
     * arrangement still in place (Week 7 Change #6); the rules live in
     * {@link EventLifecycleService}. Kept here so the existing endpoint and
     * its callers are unchanged.
     */
    @Transactional
    public EventDto confirm(UUID actingCoordinatorId, UUID eventId) {
        lifecycle.confirm(actingCoordinatorId, eventId);
        return toDto(repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId)));
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
                event.getRequiredFacilities(),
                event.getRegistrationNeeds(),
                event.getOrganisation(),
                event.getVenueRequirements(),
                event.getEquipmentRequirements(),
                event.getStatus().name(),
                coordinatorName,
                coordinatorEmail,
                statuses.forEvent(event),
                event.getSafetyFeedback(),
                event.getSafetyApprovedAt(),
                event.getConfirmedAt());
    }
}
