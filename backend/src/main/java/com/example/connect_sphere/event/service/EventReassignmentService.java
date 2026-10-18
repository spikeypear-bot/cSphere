package com.example.connect_sphere.event.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.dto.EventReassignmentRequestDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventReassignmentRequest;
import com.example.connect_sphere.event.entity.EventReassignmentStatus;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventReassignmentRequestRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.CoordinatorDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.InvalidCoordinatorException;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/** EC-C1: the assigned Coordinator asks for a transfer; the Lead decides it. */
@Service
public class EventReassignmentService {

    private static final int MAX_TEXT = 2000;
    private static final List<EventStatus> ACTIVE_STATUSES = List.of(
            EventStatus.pending, EventStatus.awaiting_safety_review, EventStatus.safety_changes_requested,
            EventStatus.safety_approved, EventStatus.confirmed);

    private final EventRepository events;
    private final EventReassignmentRequestRepository requests;
    private final EventRequestRepository eventRequests;
    private final UserRepository users;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final Clock clock;

    @Autowired
    public EventReassignmentService(EventRepository events, EventReassignmentRequestRepository requests,
            EventRequestRepository eventRequests, UserRepository users, ActivityService activity,
            NotificationService notifications) {
        this(events, requests, eventRequests, users, activity, notifications, Clock.systemUTC());
    }

    EventReassignmentService(EventRepository events, EventReassignmentRequestRepository requests,
            EventRequestRepository eventRequests, UserRepository users, ActivityService activity,
            NotificationService notifications, Clock clock) {
        this.events = events;
        this.requests = requests;
        this.eventRequests = eventRequests;
        this.users = users;
        this.activity = activity;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public EventReassignmentRequestDto request(UUID coordinatorId, UUID eventId, String rawReason) {
        String reason = requiredText(rawReason, "Provide a reason for requesting reassignment.");
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        requireActive(event);
        if (requests.existsByEventIdAndStatus(eventId, EventReassignmentStatus.pending)) {
            throw new EventReassignmentStateException("A reassignment request for this event is already pending.");
        }

        EventRequest origin = eventRequests.findByEventId(eventId)
                .orElseThrow(() -> new EventRequestNotFoundException(eventId));
        EventReassignmentRequest request = new EventReassignmentRequest();
        request.setReassignmentRequestId(UUID.randomUUID());
        request.setEventId(eventId);
        request.setRequesterUserId(coordinatorId);
        request.setReason(reason);
        request.setStatus(EventReassignmentStatus.pending);
        request.setRequestedAt(now());
        EventReassignmentRequest saved = requests.save(request);
        activity.record(new ActivityService.Entry(origin.getRequestId(), eventId,
                ActivityType.event_reassignment_requested, coordinatorId, reason, null, null, null));

        List<UUID> leads = users.findByRole(UserRole.ecl).stream().map(User::getUserId).toList();
        afterCommit(() -> notifications.createEventReassignmentRequestedNotifications(
                leads, eventId, event.getEventName(), reason));
        return toDto(saved, event, users.findById(coordinatorId).orElseThrow());
    }

    /** Caller-owned request history, or empty when the current assignee has not requested a transfer. */
    @Transactional(readOnly = true)
    public List<EventReassignmentRequestDto> latestForCoordinator(UUID coordinatorId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        boolean assigned = coordinatorId != null && coordinatorId.equals(event.getCoordinatorId());
        Optional<EventReassignmentRequest> ownLatest =
                requests.findFirstByEventIdAndRequesterUserIdOrderByRequestedAtDesc(eventId, coordinatorId);
        if (!assigned && ownLatest.isEmpty()) {
            throw new NotAssignedCoordinatorException(eventId);
        }
        return ownLatest.stream()
                .map(request -> toDto(request, event, users.findById(request.getRequesterUserId()).orElseThrow()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventReassignmentRequestDto> pendingRequests() {
        return requests.findByStatusOrderByRequestedAtAsc(EventReassignmentStatus.pending).stream()
                .map(request -> {
                    Event event = events.findById(request.getEventId())
                            .orElseThrow(() -> new EventNotFoundException(request.getEventId()));
                    User requester = users.findById(request.getRequesterUserId()).orElseThrow();
                    return toDto(request, event, requester);
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<CoordinatorDto> coordinators() {
        return users.findByRole(UserRole.ec).stream()
                .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(user -> new CoordinatorDto(user.getUserId(), user.getUsername()))
                .toList();
    }

    @Transactional
    public EventReassignmentRequestDto approve(UUID leadId, UUID requestId, UUID coordinatorId) {
        if (coordinatorId == null) {
            throw new InvalidCoordinatorException();
        }
        EventReassignmentRequest request = pendingRequest(requestId);
        Event event = events.findForUpdate(request.getEventId())
                .orElseThrow(() -> new EventNotFoundException(request.getEventId()));
        requireActive(event);
        if (!request.getRequesterUserId().equals(event.getCoordinatorId())) {
            throw new EventReassignmentStateException(
                    "The event's assignment changed after this request was submitted; refresh the request.");
        }
        if (coordinatorId.equals(event.getCoordinatorId())) {
            throw new InvalidCoordinatorException("Choose a different Event Coordinator.");
        }
        User nextCoordinator = users.findById(coordinatorId)
                .filter(user -> user.getRole() == UserRole.ec)
                .orElseThrow(() -> new InvalidCoordinatorException(coordinatorId));

        EventRequest origin = eventRequests.findByEventId(event.getEventId())
                .flatMap(row -> eventRequests.findForUpdate(row.getRequestId()))
                .orElseThrow(() -> new EventRequestNotFoundException(event.getEventId()));
        if (!request.getRequesterUserId().equals(origin.getCoordinatorId())) {
            throw new EventReassignmentStateException(
                    "The event request's assignment changed; refresh before approving this request.");
        }

        User previousCoordinator = users.findById(request.getRequesterUserId()).orElseThrow();
        event.setCoordinatorId(nextCoordinator.getUserId());
        events.save(event);
        origin.setCoordinatorId(nextCoordinator.getUserId());
        origin.setUpdatedAt(now());
        eventRequests.save(origin);

        String message = "Reassigned from " + previousCoordinator.getUsername()
                + " to " + nextCoordinator.getUsername() + ".";
        request.setStatus(EventReassignmentStatus.approved);
        request.setDecidedAt(now());
        request.setDecisionReason(message);
        EventReassignmentRequest saved = requests.save(request);
        activity.record(new ActivityService.Entry(origin.getRequestId(), event.getEventId(),
                ActivityType.event_reassignment_approved, leadId, message, null, null, null));

        UUID eventId = event.getEventId();
        String eventName = event.getEventName();
        afterCommit(() -> {
            notifications.createEventReassignmentDecisionNotifications(
                    List.of(request.getRequesterUserId()), eventId, eventName,
                    NotificationType.event_reassignment_approved, message);
            notifications.createEventReassignmentDecisionNotifications(
                    List.of(nextCoordinator.getUserId()), eventId, eventName,
                    NotificationType.event_reassignment_approved,
                    "You are now coordinating this event.");
            if (origin.getCreatedBy() != null) {
                notifications.createCoordinatorAssignmentNotification(
                        origin.getCreatedBy(), origin.getRequestId(), eventId, eventName,
                        nextCoordinator.getUsername(), nextCoordinator.getEmail(), true);
            }
        });
        return toDto(saved, event, previousCoordinator);
    }

    @Transactional
    public EventReassignmentRequestDto decline(UUID leadId, UUID requestId, String rawReason) {
        String reason = requiredText(rawReason, "Provide a reason for declining this request.");
        EventReassignmentRequest request = pendingRequest(requestId);
        Event event = events.findById(request.getEventId())
                .orElseThrow(() -> new EventNotFoundException(request.getEventId()));
        User requester = users.findById(request.getRequesterUserId()).orElseThrow();

        request.setStatus(EventReassignmentStatus.declined);
        request.setDecidedAt(now());
        request.setDecisionReason(reason);
        EventReassignmentRequest saved = requests.save(request);
        EventRequest origin = eventRequests.findByEventId(event.getEventId())
                .orElseThrow(() -> new EventRequestNotFoundException(event.getEventId()));
        activity.record(new ActivityService.Entry(origin.getRequestId(), event.getEventId(),
                ActivityType.event_reassignment_declined, leadId, reason, null, null, null));
        afterCommit(() -> notifications.createEventReassignmentDecisionNotifications(
                List.of(request.getRequesterUserId()), event.getEventId(), event.getEventName(),
                NotificationType.event_reassignment_declined, "Request declined: " + reason));
        return toDto(saved, event, requester);
    }

    private EventReassignmentRequest pendingRequest(UUID requestId) {
        EventReassignmentRequest request = requests.findForUpdate(requestId)
                .orElseThrow(() -> new EventReassignmentNotFoundException(requestId));
        if (request.getStatus() != EventReassignmentStatus.pending) {
            throw new EventReassignmentStateException("This reassignment request has already been decided.");
        }
        return request;
    }

    private EventReassignmentRequestDto toDto(
            EventReassignmentRequest request, Event event, User requester) {
        User coordinator = event.getCoordinatorId() == null
                ? null : users.findById(event.getCoordinatorId()).orElse(null);
        return new EventReassignmentRequestDto(
                request.getReassignmentRequestId(), event.getEventId(), event.getEventName(), event.getOrganisation(),
                event.getStartDatetime(), event.getEndDatetime(), requester.getUserId(), requester.getUsername(),
                request.getReason(), request.getStatus(),
                request.getRequestedAt(), request.getDecidedAt(), request.getDecisionReason(),
                coordinator == null ? null : coordinator.getUserId(),
                coordinator == null ? null : coordinator.getUsername());
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }

    private static void requireActive(Event event) {
        if (!ACTIVE_STATUSES.contains(event.getStatus())) {
            throw new EventReassignmentStateException("Completed or cancelled events cannot be reassigned.");
        }
    }

    private static String requiredText(String raw, String message) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new InvalidMessageException(message);
        }
        if (value.length() > MAX_TEXT) {
            throw new InvalidMessageException("This can be at most " + MAX_TEXT + " characters.");
        }
        return value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
