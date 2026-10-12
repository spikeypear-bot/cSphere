package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

@Service
public class ReadinessService {

    private record StandardItem(String key, String label) {}

    private static final List<StandardItem> STANDARD_ITEMS = List.of(
            new StandardItem("venue_booking", "Venue booking is approved and suitable"),
            new StandardItem("capacity_facilities", "Venue capacity and required facilities are verified"),
            new StandardItem("accessibility", "Accessibility needs are arranged"),
            new StandardItem("equipment", "Required equipment is reserved or confirmed unavailable"),
            new StandardItem("registration", "Registration and attendance arrangements are ready"),
            new StandardItem("safety_operations", "Safety, emergency, and on-site operational arrangements are ready"));
    private static final int MAX_NOTE_LENGTH = 2000;

    private final EventRepository events;
    private final ReadinessItemStateRepository itemStates;
    private final EventSafetyReviewRepository reviews;
    private final UserRepository users;
    private final EventRequestRepository eventRequests;
    private final NotificationService notifications;

    public ReadinessService(EventRepository events, ReadinessItemStateRepository itemStates,
            EventSafetyReviewRepository reviews, UserRepository users,
            EventRequestRepository eventRequests, NotificationService notifications) {
        this.events = events;
        this.itemStates = itemStates;
        this.reviews = reviews;
        this.users = users;
        this.eventRequests = eventRequests;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public ReadinessDto forSafetyOfficer(UUID eventId) {
        return readiness(eventId);
    }

    @Transactional(readOnly = true)
    public ReadinessDto forCoordinator(UUID coordinatorId, UUID eventId) {
        Event event = activeEvent(eventId);
        if (!coordinatorId.equals(event.getCoordinatorId())) {
            throw new com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException(eventId);
        }
        return readiness(event);
    }

    @Transactional
    public ReadinessDto updateItem(UUID actorId, UUID eventId, String itemKey, UpdateReadinessItemRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requirePlanning(event);
        StandardItem definition = STANDARD_ITEMS.stream()
                .filter(item -> item.key().equals(itemKey)).findFirst()
                .orElseThrow(() -> new InvalidReadinessUpdateException("Select a standard readiness item."));
        if (input == null || input.checked() == null) {
            throw new InvalidReadinessUpdateException("Provide whether the readiness item is checked.");
        }
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidReadinessUpdateException("Checklist notes must be 2000 characters or fewer.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ReadinessItemState state = itemStates.findByEventIdAndItemKey(eventId, definition.key())
                .orElseGet(() -> new ReadinessItemState(eventId, definition.key(), false, "", actorId, now));
        state.update(input.checked(), note, actorId, now);
        itemStates.save(state);
        return readiness(event);
    }

    @Transactional
    public ReadinessDto review(UUID safetyOfficerId, UUID eventId, SafetyApprovalRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requirePlanning(event);
        if (input == null || input.decision() == null) {
            throw new InvalidReadinessUpdateException("Choose approve or reject.");
        }
        String decision = input.decision().strip().toLowerCase();
        if (!decision.equals("approve") && !decision.equals("reject")) {
            throw new InvalidReadinessUpdateException("Decision must be approve or reject.");
        }
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidReadinessUpdateException("Approval notes must be 2000 characters or fewer.");
        }
        if (decision.equals("approve")) {
            List<String> outstanding = outstandingItems(eventId);
            if (!outstanding.isEmpty()) {
                throw new IncompleteReadinessException(outstanding);
            }
        }

        String status = decision.equals("approve") ? "approved" : "rejected";
        OffsetDateTime now = OffsetDateTime.now();
        EventSafetyReview review = reviews.findByEventId(eventId)
                .orElseGet(() -> new EventSafetyReview(eventId, status, note, safetyOfficerId, now));
        review.update(status, note, safetyOfficerId, now);
        reviews.save(review);

        if (decision.equals("approve")) {
            event.setStatus(EventStatus.confirmed);
            events.save(event);
            eventRequests.findByEventId(eventId)
                    .map(com.example.connect_sphere.eventrequest.entity.EventRequest::getCreatedBy)
                    .filter(java.util.Objects::nonNull)
                    .ifPresent(recipientId -> notifications.createStatusChangeNotification(
                            recipientId, null, eventId, event.getEventName(), "confirmed", note.isBlank() ? null : note));
        }
        return readiness(event);
    }

    private ReadinessDto readiness(UUID eventId) {
        return readiness(activeEvent(eventId));
    }

    private ReadinessDto readiness(Event event) {
        Map<String, ReadinessItemState> states = itemStates.findByEventId(event.getEventId()).stream()
                .collect(Collectors.toMap(ReadinessItemState::getItemKey, Function.identity()));
        List<ReadinessItemDto> items = STANDARD_ITEMS.stream().map(item -> {
            ReadinessItemState state = states.get(item.key());
            return new ReadinessItemDto(
                    item.key(), item.label(), state != null && state.isChecked(),
                    state == null ? "" : state.getNote(),
                    state == null || state.getCheckedBy() == null ? null
                            : users.findById(state.getCheckedBy()).map(User::getUsername).orElse(null),
                    state == null ? null : state.getCheckedAt(),
                    state == null || state.getUpdatedBy() == null ? null
                            : users.findById(state.getUpdatedBy()).map(User::getUsername).orElse(null),
                    state == null ? null : state.getUpdatedAt());
        }).toList();
        EventSafetyReview review = reviews.findByEventId(event.getEventId()).orElse(null);
        int completed = (int) items.stream().filter(ReadinessItemDto::checked).count();
        return new ReadinessDto(
                event.getEventId(),
                review == null ? "not_reviewed" : review.getReviewStatus(),
                review == null ? null : review.getNote(),
                review == null || review.getReviewedBy() == null ? null
                        : users.findById(review.getReviewedBy()).map(User::getUsername).orElse(null),
                review == null ? null : review.getReviewedAt(),
                completed, items.size(), items);
    }

    private List<String> outstandingItems(UUID eventId) {
        Map<String, ReadinessItemState> states = itemStates.findByEventId(eventId).stream()
                .collect(Collectors.toMap(ReadinessItemState::getItemKey, Function.identity()));
        return STANDARD_ITEMS.stream()
                .filter(item -> !states.containsKey(item.key()) || !states.get(item.key()).isChecked())
                .map(StandardItem::label)
                .toList();
    }

    private Event activeEvent(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getStatus() != EventStatus.pending && event.getStatus() != EventStatus.confirmed) {
            throw new EventNotFoundException(eventId);
        }
        return event;
    }

    private static void requirePlanning(Event event) {
        if (event.getStatus() != EventStatus.pending) {
            throw new InvalidReadinessUpdateException("Readiness and safety approval can only be changed while the event is in Planning.");
        }
    }
}
