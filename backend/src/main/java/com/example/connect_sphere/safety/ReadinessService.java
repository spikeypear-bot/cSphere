package com.example.connect_sphere.safety;

import java.time.Clock;
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
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.event.service.EventLifecycleService.SafetyDecision;
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
    @org.springframework.beans.factory.annotation.Autowired private VenueSafetyChecklist venueChecklist;
    private static final int MAX_NOTE_LENGTH = 2000;

    private final EventRepository events;
    private final ReadinessItemStateRepository itemStates;
    private final EventSafetyReviewRepository reviews;
    private final UserRepository users;
    private final EventRequestRepository eventRequests;
    private final NotificationService notifications;
    private final EventLifecycleService lifecycle;
    private final Clock clock;

    public ReadinessService(EventRepository events, ReadinessItemStateRepository itemStates,
            EventSafetyReviewRepository reviews, UserRepository users,
            EventRequestRepository eventRequests, NotificationService notifications,
            EventLifecycleService lifecycle, Clock clock) {
        this.events = events;
        this.itemStates = itemStates;
        this.reviews = reviews;
        this.users = users;
        this.eventRequests = eventRequests;
        this.notifications = notifications;
        this.lifecycle = lifecycle;
        this.clock = clock;
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
        if(venueChecklist!=null) venueChecklist.lockVenues(eventId);
        StandardItem definition = definitions(eventId).stream()
                .filter(item -> item.key().equals(itemKey)).findFirst()
                .orElseThrow(() -> new InvalidReadinessUpdateException("Select a standard readiness item."));
        if (input == null || input.checked() == null) {
            throw new InvalidReadinessUpdateException("Provide whether the readiness item is checked.");
        }
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidReadinessUpdateException("Checklist notes must be 2000 characters or fewer.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        ReadinessItemState state = itemStates.findByEventIdAndItemKey(eventId, definition.key())
                .orElseGet(() -> new ReadinessItemState(eventId, definition.key(), false, "", actorId, now));
        if(!java.util.Objects.equals(state.getArrangementFingerprint(),fingerprint(eventId,definition.key())))
            state.update(false,note,actorId,now);
        state.update(input.checked(), note, actorId, now);
        state.setArrangementFingerprint(fingerprint(eventId,definition.key()));
        itemStates.save(state);
        return readiness(event);
    }

    /**
     * SO-C4 and EC08: the Safety Officer's decision on an event the
     * coordinator sent for review. Approval still needs every checklist item
     * ticked; it marks the event Safety Approved so the coordinator can
     * confirm it (EC08). Rejecting or requesting changes sends it back to the
     * coordinator with the note as feedback (Week 7: "the event returns to
     * the relevant earlier planning stage"). The status change, timeline
     * entry and coordinator notification are EventLifecycleService's.
     */
    @Transactional
    public ReadinessDto review(UUID safetyOfficerId, UUID eventId, SafetyApprovalRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getStatus() != EventStatus.awaiting_safety_review) {
            throw new InvalidReadinessUpdateException(event.getStatus() == EventStatus.pending
                    ? "The Event Coordinator has not sent this event for its safety check yet."
                    : "A safety decision can only be made while the event is awaiting safety review.");
        }
        if (input == null || input.decision() == null) {
            throw new InvalidReadinessUpdateException("Choose approve, request changes or reject.");
        }
        String decision = input.decision().strip().toLowerCase();
        SafetyDecision outcome = switch (decision) {
            case "approve" -> SafetyDecision.APPROVE;
            case "request_changes" -> SafetyDecision.REQUEST_CHANGES;
            case "reject" -> SafetyDecision.REJECT;
            default -> throw new InvalidReadinessUpdateException("Decision must be approve, request_changes or reject.");
        };
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidReadinessUpdateException("Approval notes must be 2000 characters or fewer.");
        }
        if (outcome == SafetyDecision.APPROVE) {
            if(venueChecklist!=null) venueChecklist.lockVenues(eventId);
            List<String> outstanding = outstandingItems(eventId);
            if (!outstanding.isEmpty()) {
                throw new IncompleteReadinessException(outstanding);
            }
        } else if (note.isEmpty()) {
            throw new InvalidReadinessUpdateException(outcome == SafetyDecision.REJECT
                    ? "Explain why the safety arrangement is rejected, so the coordinator knows what to fix."
                    : "Explain what needs to change, so the coordinator knows what to fix.");
        }
        List<String> flagged = flaggedItems(eventId, outcome, input.flaggedItems());
        String status = switch (outcome) {
            case APPROVE -> "approved";
            case REQUEST_CHANGES -> "changes_requested";
            case REJECT -> "rejected";
        };
        OffsetDateTime now = OffsetDateTime.now(clock);
        EventSafetyReview review = reviews.findByEventId(eventId)
                .orElseGet(() -> new EventSafetyReview(eventId, status, note, safetyOfficerId, now));
        review.update(status, note, flagged, safetyOfficerId, now);
        reviews.save(review);
        lifecycle.recordSafetyDecision(safetyOfficerId, eventId, outcome, note.isEmpty() ? null : note, flagged);
        return readiness(event);
    }

    /**
     * SO-C5/SO-C6: a rejection or a request for changes names at least one
     * checklist item; an approval names none. Returned in checklist order,
     * once each.
     */
    private List<String> flaggedItems(UUID eventId, SafetyDecision outcome, List<String> requested) {
        List<String> given = requested == null ? List.of() : requested;
        if (outcome == SafetyDecision.APPROVE) {
            if (!given.isEmpty()) {
                throw new InvalidReadinessUpdateException(
                        "Checklist items are only selected when requesting changes or rejecting.");
            }
            return List.of();
        }
        if (given.isEmpty()) {
            throw new InvalidReadinessUpdateException(outcome == SafetyDecision.REJECT
                    ? "Select at least one checklist item the rejection concerns."
                    : "Select at least one checklist item that needs changing.");
        }
        List<String> unknown = given.stream()
                .filter(key -> definitions(eventId).stream().noneMatch(item -> item.key().equals(key))).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidReadinessUpdateException("Unknown checklist item(s): " + String.join(", ", unknown) + ".");
        }
        return definitions(eventId).stream().map(StandardItem::key).filter(given::contains).toList();
    }

    private ReadinessDto readiness(UUID eventId) {
        return readiness(activeEvent(eventId));
    }

    private ReadinessDto readiness(Event event) {
        Map<String, ReadinessItemState> states = itemStates.findByEventId(event.getEventId()).stream()
                .collect(Collectors.toMap(ReadinessItemState::getItemKey, Function.identity()));
        List<ReadinessItemDto> items = definitions(event.getEventId()).stream().map(item -> {
            ReadinessItemState state = states.get(item.key());
            return new ReadinessItemDto(
                    item.key(), item.label(), current(event.getEventId(),item.key(),state),
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
                review == null ? List.of() : List.copyOf(review.getFlaggedItems()),
                completed, items.size(), items);
    }

    private List<String> outstandingItems(UUID eventId) {
        Map<String, ReadinessItemState> states = itemStates.findByEventId(eventId).stream()
                .collect(Collectors.toMap(ReadinessItemState::getItemKey, Function.identity()));
        return definitions(eventId).stream()
                .filter(item -> !current(eventId,item.key(),states.get(item.key())))
                .map(StandardItem::label)
                .toList();
    }

    private List<StandardItem> definitions(UUID eventId) {
        var result=new java.util.ArrayList<>(STANDARD_ITEMS);
        if(venueChecklist!=null) venueChecklist.items(eventId).forEach(i -> result.add(new StandardItem(i.key(),i.label())));
        return result;
    }
    private String fingerprint(UUID eventId,String key) {
        if(!key.startsWith("venue_safety_") || venueChecklist==null) return null;
        return venueChecklist.items(eventId).stream().filter(i -> i.key().equals(key)).map(VenueSafetyChecklist.Item::fingerprint).findFirst().orElse(null);
    }
    private boolean current(UUID eventId,String key,ReadinessItemState state) {
        return state!=null && state.isChecked() && java.util.Objects.equals(state.getArrangementFingerprint(),fingerprint(eventId,key));
    }

    private Event activeEvent(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!EventStatus.ACTIVE.contains(event.getStatus())) {
            throw new EventNotFoundException(eventId);
        }
        return event;
    }

    /** The checklist can be worked on in Planning and while the Safety Officer is reviewing. */
    private static void requirePlanning(Event event) {
        if (!event.getStatus().isPlanningEditable() && event.getStatus() != EventStatus.awaiting_safety_review) {
            throw new InvalidReadinessUpdateException("Readiness can only be changed before the safety check is decided.");
        }
    }
}
