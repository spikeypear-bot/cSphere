package com.example.connect_sphere.event.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.dto.EventCancellationRequestDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventCancellationRequest;
import com.example.connect_sphere.event.entity.EventCancellationStatus;
import com.example.connect_sphere.event.repository.EventCancellationRequestRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.eventrequest.service.MissingOrganisationException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO07: an Event Organiser stops ConnectSphere arranging an event that will
 * no longer take place (decision-log D27).
 *
 * <ul>
 * <li>Before approval (Submitted, Under Review, Clarification Required) the
 * organiser cancels the request at once.</li>
 * <li>After approval (Planning to Confirmed) the organiser requests
 * cancellation; the event keeps its status and arrangements until the
 * event's currently assigned coordinator decides. The pending request
 * belongs to the event, so after a reassignment the new coordinator
 * decides it.</li>
 * </ul>
 *
 * <p>Every step takes a row lock first, so it acts on the current state and
 * a late or repeated action is refused (AC13). The timeline entry is written
 * in the same transaction; notifications only after it commits (D21), so a
 * failed action changes nothing and tells no one (AC14).
 */
@Service
public class EventCancellationService {

    static final int MAX_TEXT = 2000;

    /** AC3: the event statuses an organiser may ask to cancel (Planning to
     * Confirmed). A Confirmed event whose end has passed is Completed, which
     * {@link JourneyStatusResolver#journeyOf} already reports. */
    static final Set<JourneyStatus> CANCELLABLE = EnumSet.of(JourneyStatus.PLANNING,
            JourneyStatus.AWAITING_SAFETY_REVIEW, JourneyStatus.SAFETY_CHANGES_REQUESTED,
            JourneyStatus.SAFETY_APPROVED, JourneyStatus.CONFIRMED);

    private final EventRepository events;
    private final EventCancellationRequestRepository cancellations;
    private final UserRepository users;
    private final EventRequestRepository eventRequests;
    private final EventRequestMapper requestMapper;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final JourneyStatusResolver statuses;
    private final Clock clock;

    @Autowired
    public EventCancellationService(EventRepository events, EventCancellationRequestRepository cancellations,
            UserRepository users, EventRequestRepository eventRequests, EventRequestMapper requestMapper,
            ActivityService activity, NotificationService notifications, JourneyStatusResolver statuses) {
        this(events, cancellations, users, eventRequests, requestMapper, activity, notifications, statuses,
                Clock.systemUTC());
    }

    EventCancellationService(EventRepository events, EventCancellationRequestRepository cancellations,
            UserRepository users, EventRequestRepository eventRequests, EventRequestMapper requestMapper,
            ActivityService activity, NotificationService notifications, JourneyStatusResolver statuses,
            Clock clock) {
        this.events = events;
        this.cancellations = cancellations;
        this.users = users;
        this.eventRequests = eventRequests;
        this.requestMapper = requestMapper;
        this.activity = activity;
        this.notifications = notifications;
        this.statuses = statuses;
        this.clock = clock;
    }

    // ---- Before approval: cancel the request at once (AC2) ------------------

    @Transactional
    public EventRequestDto cancelRequest(String organisation, UUID organiserId, UUID requestId, String rawReason) {
        requireOrganisation(organisation);
        EventRequest request = eventRequests.findForUpdate(requestId)
                .filter(r -> organisation.equals(r.getOrganisation()))
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        EventRequestStatus from = request.getStatus();
        if (from != EventRequestStatus.pending && from != EventRequestStatus.clarification_required) {
            throw new EventCancellationStateException(
                    "Only a request that is Submitted, Under Review or Clarification Required can be cancelled "
                            + "(this one is " + statuses.forRequest(request).label() + ")."
                            + (from == EventRequestStatus.approved
                                    ? " Request cancellation of the event instead." : ""));
        }
        String reason = requiredReason(rawReason, "Please give a reason for cancelling this request.");

        request.setStatus(EventRequestStatus.cancelled);
        request.setUpdatedAt(now());
        EventRequest saved = eventRequests.save(request);
        activity.record(new ActivityService.Entry(saved.getRequestId(), null, ActivityType.request_cancelled,
                organiserId, reason, null, from.name(), EventRequestStatus.cancelled.name()));

        UUID coordinatorId = saved.getCoordinatorId();
        if (coordinatorId != null) {
            UUID id = saved.getRequestId();
            String name = saved.getEventName();
            afterCommit(() -> notifications.createRequestCancelledNotification(coordinatorId, id, name, reason));
        }
        return requestMapper.toDto(saved);
    }

    // ---- After approval: request cancellation of the event (AC3, AC6) --------

    /**
     * The organiser asks for their approved event to be cancelled. Nothing
     * about the event changes yet (AC3): its status, bookings and equipment
     * stay as they are until the assigned coordinator decides.
     */
    @Transactional
    public EventCancellationRequestDto requestCancellation(String organisation, UUID organiserId, UUID requestId,
            String rawReason) {
        requireOrganisation(organisation);
        EventRequest origin = eventRequests.findById(requestId)
                .filter(r -> organisation.equals(r.getOrganisation()))
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (origin.getStatus() != EventRequestStatus.approved || origin.getEventId() == null) {
            boolean underReview = origin.getStatus() == EventRequestStatus.pending
                    || origin.getStatus() == EventRequestStatus.clarification_required;
            throw new EventCancellationStateException(underReview
                    ? "This request has not been approved yet, so cancel the request itself instead."
                    : "Only an approved event can have its cancellation requested (this one is "
                            + statuses.forRequest(origin).label() + ").");
        }
        // The event row lock serialises this with every other change to the
        // event: a second request, a decision, a reassignment.
        Event event = events.findForUpdate(origin.getEventId())
                .orElseThrow(() -> new EventNotFoundException(origin.getEventId()));
        requireCancellable(event);
        if (cancellations.existsByEventIdAndStatus(event.getEventId(), EventCancellationStatus.pending)) {
            throw new EventCancellationStateException(
                    "A cancellation request for this event is already waiting for the coordinator's decision.");
        }
        String reason = requiredReason(rawReason, "Please give a reason for cancelling this event.");

        EventCancellationRequest request = new EventCancellationRequest();
        request.setCancellationRequestId(UUID.randomUUID());
        request.setEventId(event.getEventId());
        request.setRequesterUserId(organiserId);
        request.setReason(reason);
        request.setStatus(EventCancellationStatus.pending);
        request.setRequestedAt(now());
        EventCancellationRequest saved = cancellations.save(request);
        activity.record(new ActivityService.Entry(origin.getRequestId(), event.getEventId(),
                ActivityType.event_cancellation_requested, organiserId, reason, null, null, null));

        UUID coordinatorId = event.getCoordinatorId();
        if (coordinatorId != null) {
            UUID eventId = event.getEventId();
            String name = event.getEventName();
            afterCommit(() -> notifications.createEventCancellationRequestedNotification(
                    coordinatorId, eventId, name, reason));
        }
        return toDto(saved, event);
    }

    /**
     * The event's cancellation requests, newest first, so a pending one leads
     * (AC6, AC7). The owning organisation's organisers, the assigned
     * coordinator and the Lead (oversight, read-only) may read them.
     */
    @Transactional(readOnly = true)
    public List<EventCancellationRequestDto> forEvent(UserRole viewerRole, String organisation, UUID viewerId,
            UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (viewerRole == UserRole.ec) {
            requireAssigned(viewerId, event);
        } else if (viewerRole == UserRole.eo) {
            if (organisation == null || !organisation.equals(event.getOrganisation())) {
                throw new EventNotFoundException(eventId);
            }
        } else if (viewerRole != UserRole.ecl) {
            // SecurityConfig lets only these three in; anyone else learns nothing.
            throw new EventNotFoundException(eventId);
        }
        return cancellations.findByEventIdOrderByRequestedAtDesc(eventId).stream()
                .map(request -> toDto(request, event)).toList();
    }

    // ---- helpers ---------------------------------------------------------------

    /** AC3/AC5/AC12: only an event from Planning to Confirmed; never a
     * Cancelled or Completed one. */
    private void requireCancellable(Event event) {
        JourneyStatus journey = statuses.journeyOf(event);
        if (!CANCELLABLE.contains(journey)) {
            throw new EventCancellationStateException("Only an event in Planning, Awaiting Safety Review, "
                    + "Safety Changes Requested, Safety Approved or Confirmed can be cancelled (this one is "
                    + journey.label() + ").");
        }
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }

    private static void requireOrganisation(String organisation) {
        if (organisation == null || organisation.isBlank()) {
            throw new MissingOrganisationException();
        }
    }

    private EventCancellationRequestDto toDto(EventCancellationRequest request, Event event) {
        return new EventCancellationRequestDto(request.getCancellationRequestId(), event.getEventId(),
                event.getEventName(), nameOf(request.getRequesterUserId()), request.getReason(),
                request.getStatus(), request.getRequestedAt(), nameOf(request.getDecidedBy()),
                request.getDecidedAt(), request.getDecisionReason());
    }

    private String nameOf(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::getUsername).orElse(null);
    }

    /** Trimmed, 1–2000 characters (AC4), or a message fit to show the user. */
    static String requiredReason(String raw, String blankMessage) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new InvalidMessageException(blankMessage);
        }
        if (text.length() > MAX_TEXT) {
            throw new InvalidMessageException("A reason can be at most " + MAX_TEXT
                    + " characters (this one is " + text.length() + ").");
        }
        return text;
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
