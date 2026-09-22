package com.example.connect_sphere.event.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * The Event Coordinator/Organiser-facing half of EO09's "Confirmed"
 * notification and "can view the confirmed event arrangements" AC — see
 * EventRequestService for how an {@link Event} first comes to exist
 * (approval).
 */
@Service
public class EventService {

    private final EventRepository repository;
    private final EventRequestRepository eventRequestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public EventService(
            EventRepository repository,
            EventRequestRepository eventRequestRepository,
            UserRepository userRepository,
            NotificationService notificationService) {
        this.repository = repository;
        this.eventRequestRepository = eventRequestRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
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

    /** EO09 "Confirmed". */
    @Transactional
    public EventDto confirm(UUID actingCoordinatorId, UUID eventId) {
        Event event = repository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getStatus() != EventStatus.pending) {
            throw new EventNotPendingException(eventId, event.getStatus());
        }
        if (!actingCoordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(eventId);
        }
        event.setStatus(EventStatus.confirmed);
        Event saved = repository.save(event);

        // Events carry no direct reference back to who created the request
        // that became them — only the reverse link (EventRequest.eventId),
        // set once at approval time. See EventRequestRepository.findByEventId.
        eventRequestRepository.findByEventId(eventId)
                .map(EventRequest::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .ifPresent(recipientId -> notificationService.createStatusChangeNotification(
                        recipientId, null, saved.getEventId(), saved.getEventName(), "confirmed", null));

        return toDto(saved);
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
