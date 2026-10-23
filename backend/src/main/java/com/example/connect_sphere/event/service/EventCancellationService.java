package com.example.connect_sphere.event.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.eventrequest.service.MissingOrganisationException;
import com.example.connect_sphere.notification.service.NotificationService;

/**
 * EO07: an Event Organiser stops ConnectSphere arranging an event that will
 * no longer take place (decision-log D27).
 *
 * <ul>
 * <li>Before approval (Submitted, Under Review, Clarification Required) the
 * organiser cancels the request at once.</li>
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

    private final EventRequestRepository eventRequests;
    private final EventRequestMapper requestMapper;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final JourneyStatusResolver statuses;
    private final Clock clock;

    @Autowired
    public EventCancellationService(EventRequestRepository eventRequests, EventRequestMapper requestMapper,
            ActivityService activity, NotificationService notifications, JourneyStatusResolver statuses) {
        this(eventRequests, requestMapper, activity, notifications, statuses, Clock.systemUTC());
    }

    EventCancellationService(EventRequestRepository eventRequests, EventRequestMapper requestMapper,
            ActivityService activity, NotificationService notifications, JourneyStatusResolver statuses,
            Clock clock) {
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
        if (organisation == null || organisation.isBlank()) {
            throw new MissingOrganisationException();
        }
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

    // ---- helpers ---------------------------------------------------------------

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
