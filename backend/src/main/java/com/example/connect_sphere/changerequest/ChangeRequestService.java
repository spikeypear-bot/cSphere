package com.example.connect_sphere.changerequest;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.changerequest.ChangeRequestDto.FieldChange;
import com.example.connect_sphere.changerequest.ChangeRequestDto.ImpactItem;
import com.example.connect_sphere.changerequest.ChangeRequestDto.View;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO03 (the organiser asks for a change) and EC-NEW3 (the assigned coordinator
 * decides it). Nothing about the request or its event changes until approval;
 * approval applies every change at once and, when the change affects venue,
 * equipment or safety arrangements of an event already in the safety stages,
 * sends it back to Planning (EC08). Each step is one transaction with its
 * change-log entry (EC-NEW5); notifications are sent after commit.
 *
 * Who may see what: organisers by organisation (AU04), coordinators by
 * assignment (ECL-C7, refused with the shared 403), the Lead everything,
 * read-only.
 */
@Service
public class ChangeRequestService {

    static final int MAX_TEXT = 2000;
    static final String REQUEST_CHANGED_SINCE = "This request has changed since the change was asked for. "
            + "The organiser needs to withdraw it and ask again.";

    /** Who is asking: the token's role, user and organisation. */
    public record Caller(UserRole role, UUID userId, String organisation) {
    }

    private final ChangeRequestRepository changeRequests;
    private final EventRequestRepository requests;
    private final EventRepository events;
    private final EventRequestService requestRules;
    private final EventLifecycleService lifecycle;
    private final ChangeImpactService impact;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final UserRepository users;
    private final Clock clock;
    private final VenueRequirementChangeService venueRequirements;
    /** EC-NEW3 (D37): absent in plain unit tests, where no equipment is flagged. */
    @Autowired(required = false) private EquipmentRequestAttentionService equipmentAttention;

    @Autowired
    public ChangeRequestService(ChangeRequestRepository changeRequests, EventRequestRepository requests,
            EventRepository events, EventRequestService requestRules, EventLifecycleService lifecycle,
            ChangeImpactService impact, ActivityService activity, NotificationService notifications,
            UserRepository users, VenueRequirementChangeService venueRequirements) {
        this(changeRequests, requests, events, requestRules, lifecycle, impact, activity, notifications, users,
                Clock.systemUTC(), venueRequirements);
    }

    ChangeRequestService(ChangeRequestRepository changeRequests, EventRequestRepository requests,
            EventRepository events, EventRequestService requestRules, EventLifecycleService lifecycle,
            ChangeImpactService impact, ActivityService activity, NotificationService notifications,
            UserRepository users, Clock clock, VenueRequirementChangeService venueRequirements) {
        this.changeRequests = changeRequests;
        this.requests = requests;
        this.events = events;
        this.requestRules = requestRules;
        this.lifecycle = lifecycle;
        this.impact = impact;
        this.activity = activity;
        this.notifications = notifications;
        this.users = users;
        this.clock = clock;
        this.venueRequirements = venueRequirements;
    }

    /** TS11: announces approved changes (ChangeApproved) to listeners that tell
     * staff after commit. Setter-injected, like EquipmentRequestService's
     * publisher, so plain unit tests need not supply one. */
    private org.springframework.context.ApplicationEventPublisher published;

    @Autowired(required = false)
    void setPublisher(org.springframework.context.ApplicationEventPublisher published) {
        this.published = published;
    }

    // ---- EO03: ask ---------------------------------------------------------------

    @Transactional
    public View create(Caller organiser, UUID requestId, String rawReason, SaveEventRequestRequest details) {
        String reason = requiredText(rawReason, "Please say why the change is needed.");
        if (details == null) {
            throw new InvalidMessageException("Please include the event's details as they should be.");
        }
        EventRequest origin = requests.findForUpdate(requestId)
                .filter(r -> sameOrganisation(organiser, r))
                .orElseThrow(() -> new ChangeRequestException.NotFound(requestId));
        Event event = origin.getEventId() == null ? null
                : events.findForUpdate(origin.getEventId()).orElse(null);
        notChangeable(origin, event).ifPresent(why -> {
            throw new ChangeRequestException.State(why);
        });
        if (changeRequests.findFirstByRequestIdAndStatus(requestId, ChangeRequestStatus.pending).isPresent()) {
            throw new ChangeRequestException.State(
                    "This event already has a change request waiting for a decision. Withdraw it or wait for the decision.");
        }
        // Same rules as submitting the request (EO02, EO-C2): 422 per field.
        requestRules.requireValidDetails(details);

        Map<String, Object> before = ChangeFields.valuesOf(EventRequestService.detailsOf(origin));
        Map<String, Object> after = ChangeFields.valuesOf(details);
        List<FieldChange> changes = ChangeFields.changes(before, after);
        if (changes.isEmpty()) {
            throw new InvalidMessageException("Nothing was changed. Change at least one detail, or cancel.");
        }

        ChangeRequest change = new ChangeRequest();
        change.setChangeRequestId(UUID.randomUUID());
        change.setRequestId(requestId);
        change.setEventId(origin.getEventId());
        change.setRequestedBy(organiser.userId());
        change.setReason(reason);
        change.setOriginalValues(before);
        change.setProposedValues(after);
        change.setStatus(ChangeRequestStatus.pending);
        change.setRequestedAt(now());
        ChangeRequest saved;
        try {
            saved = changeRequests.saveAndFlush(change);
        } catch (DataIntegrityViolationException alreadyPending) {
            // Two submissions at the same moment: the index lets one through.
            throw new ChangeRequestException.State(
                    "This event already has a change request waiting for a decision.");
        }
        activity.record(new ActivityService.Entry(requestId, origin.getEventId(), ActivityType.change_requested,
                organiser.userId(), fitActivityMessage(reason + " — " + ChangeFields.describe(changes)), null, null, null, null,
                ChangeFields.changedValues(changes)));

        UUID coordinator = coordinatorOf(origin, event);
        List<UUID> recipients = coordinator != null ? List.of(coordinator)
                : users.findByRole(UserRole.ecl).stream().map(User::getUserId).toList();
        String name = nameOf(origin, event);
        notifications.createChangeRequestNotifications(recipients,
                NotificationType.change_request_submitted, requestId, origin.getEventId(), name,
                "Change requested: " + ChangeFields.describe(changes) + ". Reason: " + reason);
        return view(saved, organiser, origin, event);
    }

    // ---- EO03: withdraw ----------------------------------------------------------

    @Transactional
    public View withdraw(Caller organiser, UUID changeRequestId) {
        ChangeRequest change = changeRequests.findForUpdate(changeRequestId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        EventRequest origin = requests.findById(change.getRequestId())
                .filter(r -> sameOrganisation(organiser, r))
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        requirePending(change);
        change.setStatus(ChangeRequestStatus.withdrawn);
        change.setDecidedBy(organiser.userId());
        change.setDecidedAt(now());
        ChangeRequest saved = changeRequests.save(change);
        Event event = eventOf(origin);
        activity.record(new ActivityService.Entry(origin.getRequestId(), origin.getEventId(),
                ActivityType.change_request_withdrawn, organiser.userId(), "Withdrew the change request.",
                null, null, null));
        UUID coordinator = coordinatorOf(origin, event);
        if (coordinator != null) {
            String name = nameOf(origin, event);
            notifications.createChangeRequestNotifications(List.of(coordinator),
                    NotificationType.change_request_withdrawn, origin.getRequestId(), origin.getEventId(), name,
                    "The organiser withdrew their change request.");
        }
        return view(saved, organiser, origin, event);
    }

    // ---- EC-NEW3: decide -----------------------------------------------------------

    @Transactional
    public View approve(Caller coordinator, UUID changeRequestId, List<String> acknowledged) {
        ChangeRequest change = changeRequests.findForUpdate(changeRequestId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        EventRequest origin = requests.findForUpdate(change.getRequestId())
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        Event event = origin.getEventId() == null ? null
                : events.findForUpdate(origin.getEventId()).orElse(null);
        requireDecider(coordinator, origin, event);
        requirePending(change);
        notDecidable(origin, event).ifPresent(why -> {
            throw new ChangeRequestException.State(why);
        });
        // The proposal is a full copy of the details as they were when it was asked for. If
        // the request has changed since (e.g. a clarification answered), applying it would
        // silently undo those edits, so the organiser must withdraw and ask again.
        Map<String, Object> currentValues = ChangeFields.valuesOf(EventRequestService.detailsOf(origin));
        if (!ChangeFields.changes(change.getOriginalValues(), currentValues).isEmpty()) {
            throw new ChangeRequestException.State(REQUEST_CHANGED_SINCE);
        }
        SaveEventRequestRequest proposed = ChangeFields.detailsOf(change.getProposedValues());
        // Re-checked now: a proposed start may have passed while it waited.
        requestRules.requireValidDetails(proposed);

        List<FieldChange> changes = ChangeFields.changes(currentValues, change.getProposedValues());
        Set<String> changed = fields(changes);
        List<ImpactItem> current = impact.assess(event, proposed, changed);
        Set<String> confirmed = acknowledged == null ? Set.of() : new HashSet<>(acknowledged);
        if (!confirmed.containsAll(ChangeImpactService.attentionKeys(current))) {
            throw new ChangeRequestException.ImpactChanged(current);
        }

        boolean replan = event != null && EventStatus.IN_OR_PAST_SAFETY_CHECK.contains(event.getStatus())
                && changed.stream().anyMatch(ChangeFields.AFFECTS_ARRANGEMENTS::contains);
        OffsetDateTime oldStart = event == null ? null : event.getStartDatetime();
        OffsetDateTime oldEnd = event == null ? null : event.getEndDatetime();
        requestRules.applyApprovedDetails(origin, event, proposed);
        change.setStatus(ChangeRequestStatus.approved);
        change.setDecidedBy(coordinator.userId());
        change.setDecidedAt(now());
        change.setAcknowledgedImpacts(current.stream().filter(i -> ChangeImpactService.ATTENTION.equals(i.status()))
                .map(i -> Map.of("key", i.key(), "area", i.area(), "title", i.title(), "detail", i.detail()))
                .toList());
        ChangeRequest saved = changeRequests.save(change);
        String summary = ChangeFields.describe(changes);
        activity.record(new ActivityService.Entry(origin.getRequestId(), origin.getEventId(),
                ActivityType.change_approved, coordinator.userId(), fitActivityMessage("Approved: " + summary), null, null, null, null,
                ChangeFields.changedValues(changes)));
        venueRequirements.approved(saved, event, oldStart, oldEnd);
        if (equipmentAttention != null) equipmentAttention.approved(saved, event, changed, proposed);
        if (replan) {
            lifecycle.returnToPlanning(event.getEventId(), coordinator.userId(),
                    fitActivityMessage("An approved change affects the event's arrangements: " + summary));
        }

        if (published != null && origin.getEventId() != null) {
            published.publishEvent(new ChangeApproved(saved.getChangeRequestId(), origin.getEventId(),
                    Map.copyOf(withoutNulls(currentValues)), Map.copyOf(withoutNulls(saved.getProposedValues())),
                    Set.copyOf(changed), saved.getDecidedAt()));
        }

        String name = nameOf(origin, event);
        notifications.createChangeRequestNotifications(List.of(change.getRequestedBy()),
                NotificationType.change_request_decided, origin.getRequestId(), origin.getEventId(), name,
                "Your change request was approved: " + summary
                        + (replan ? ". The arrangements will be re-confirmed." : "."));
        return view(saved, coordinator, origin, eventOf(origin));
    }

    @Transactional
    public View reject(Caller coordinator, UUID changeRequestId, String rawReason) {
        String reason = requiredText(rawReason, "Please give the organiser a reason for rejecting the change.");
        ChangeRequest change = changeRequests.findForUpdate(changeRequestId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        EventRequest origin = requests.findById(change.getRequestId())
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        Event event = eventOf(origin);
        requireDecider(coordinator, origin, event);
        requirePending(change);
        change.setStatus(ChangeRequestStatus.rejected);
        change.setDecidedBy(coordinator.userId());
        change.setDecidedAt(now());
        change.setDecisionReason(reason);
        ChangeRequest saved = changeRequests.save(change);
        activity.record(new ActivityService.Entry(origin.getRequestId(), origin.getEventId(),
                ActivityType.change_rejected, coordinator.userId(), fitActivityMessage("Rejected: " + reason), null, null, null));
        String name = nameOf(origin, event);
        notifications.createChangeRequestNotifications(List.of(change.getRequestedBy()),
                NotificationType.change_request_decided, origin.getRequestId(), origin.getEventId(), name,
                "Your change request was rejected: " + reason);
        return view(saved, coordinator, origin, event);
    }

    // ---- reading ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public View get(Caller caller, UUID changeRequestId) {
        ChangeRequest change = changeRequests.findById(changeRequestId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        EventRequest origin = requests.findById(change.getRequestId())
                .orElseThrow(() -> new ChangeRequestException.NotFound(changeRequestId));
        Event event = eventOf(origin);
        requireViewer(caller, origin, event, changeRequestId);
        return view(change, caller, origin, event);
    }

    /** One request's change requests, newest first. */
    @Transactional(readOnly = true)
    public List<View> forRequest(Caller caller, UUID requestId) {
        EventRequest origin = requests.findById(requestId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(requestId));
        Event event = eventOf(origin);
        requireViewer(caller, origin, event, requestId);
        return changeRequests.findByRequestIdOrderByRequestedAtDesc(requestId).stream()
                .map(change -> view(change, caller, origin, event)).toList();
    }

    /** The same, found from the event (pages that show an event, not its request). */
    @Transactional(readOnly = true)
    public List<View> forEvent(Caller caller, UUID eventId) {
        EventRequest origin = requests.findByEventId(eventId)
                .orElseThrow(() -> new ChangeRequestException.NotFound(eventId));
        return forRequest(caller, origin.getRequestId());
    }

    /** Every pending change request this caller may see, oldest first: their
     * organisation's, their assigned events', or (the Lead) all. */
    @Transactional(readOnly = true)
    public List<View> pending(Caller caller) {
        List<View> views = new ArrayList<>();
        for (ChangeRequest change : changeRequests.findByStatusOrderByRequestedAtAsc(ChangeRequestStatus.pending)) {
            EventRequest origin = requests.findById(change.getRequestId()).orElse(null);
            if (origin == null) {
                continue;
            }
            Event event = eventOf(origin);
            if (mayView(caller, origin, event)) {
                views.add(view(change, caller, origin, event));
            }
        }
        views.sort(Comparator.comparing(View::requestedAt));
        return views;
    }

    // ---- rules ----------------------------------------------------------------------

    /** Why the organiser cannot ask for a change now, if they cannot. */
    private Optional<String> notChangeable(EventRequest origin, Event event) {
        return switch (origin.getStatus()) {
            case draft -> Optional.of("A draft is edited directly. Open it and change the details there.");
            case clarification_required -> Optional.of(
                    "The coordinator has asked you a question about this request. Update it in your reply instead.");
            case rejected -> Optional.of("This request was rejected, so it can no longer be changed.");
            case cancelled -> Optional.of("This request was cancelled, so it can no longer be changed.");
            case pending, approved -> started(origin, event).or(() -> inactive(origin, event));
        };
    }

    /** Why the coordinator cannot decide now, if they cannot. */
    private Optional<String> notDecidable(EventRequest origin, Event event) {
        if (origin.getStatus() == EventRequestStatus.clarification_required) {
            return Optional.of("The request is waiting for the organiser's clarification; decide this change once they reply.");
        }
        return notChangeable(origin, event);
    }

    private Optional<String> started(EventRequest origin, Event event) {
        OffsetDateTime start = event != null ? event.getStartDatetime() : origin.getStartDatetime();
        return start != null && !start.toInstant().isAfter(clock.instant())
                ? Optional.of("The event has already started, so it can no longer be changed.")
                : Optional.empty();
    }

    private static Optional<String> inactive(EventRequest origin, Event event) {
        if (origin.getStatus() == EventRequestStatus.approved && (event == null || !EventStatus.ACTIVE.contains(event.getStatus()))) {
            return Optional.of("This event is no longer active, so it can no longer be changed.");
        }
        return Optional.empty();
    }

    private void requireDecider(Caller caller, EventRequest origin, Event event) {
        UUID coordinator = coordinatorOf(origin, event);
        if (caller.role() != UserRole.ec || coordinator == null || !coordinator.equals(caller.userId())) {
            throw new NotAssignedCoordinatorException(origin.getRequestId());
        }
    }

    private void requireViewer(Caller caller, EventRequest origin, Event event, UUID id) {
        if (caller.role() == UserRole.eo && !sameOrganisation(caller, origin)) {
            throw new ChangeRequestException.NotFound(id);
        }
        if (caller.role() == UserRole.ec && !caller.userId().equals(coordinatorOf(origin, event))) {
            throw new NotAssignedCoordinatorException(origin.getRequestId());
        }
        if (!Set.of(UserRole.eo, UserRole.ec, UserRole.ecl).contains(caller.role())) {
            throw new ChangeRequestException.NotFound(id);
        }
    }

    private boolean mayView(Caller caller, EventRequest origin, Event event) {
        return switch (caller.role()) {
            case eo -> sameOrganisation(caller, origin);
            case ec -> caller.userId().equals(coordinatorOf(origin, event));
            case ecl -> true;
            default -> false;
        };
    }

    private static void requirePending(ChangeRequest change) {
        if (change.getStatus() != ChangeRequestStatus.pending) {
            throw new ChangeRequestException.State("This change request has already been "
                    + change.getStatus().name() + ".");
        }
    }

    private static boolean sameOrganisation(Caller caller, EventRequest origin) {
        return caller.organisation() != null && caller.organisation().equals(origin.getOrganisation());
    }

    /** Once the event exists its coordinator is the source of truth (ECL-C7). */
    private static UUID coordinatorOf(EventRequest origin, Event event) {
        return event != null ? event.getCoordinatorId() : origin.getCoordinatorId();
    }

    private Event eventOf(EventRequest origin) {
        return origin.getEventId() == null ? null : events.findById(origin.getEventId()).orElse(null);
    }

    private static String nameOf(EventRequest origin, Event event) {
        return event != null ? event.getEventName() : origin.getEventName();
    }

    private static Set<String> fields(List<FieldChange> changes) {
        Set<String> fields = new HashSet<>();
        changes.forEach(c -> fields.add(c.field()));
        return fields;
    }

    private View view(ChangeRequest change, Caller caller, EventRequest origin, Event event) {
        boolean pending = change.getStatus() == ChangeRequestStatus.pending;
        // Compared with what it was asked against, so the record stays readable
        // after approval.
        List<FieldChange> changes = ChangeFields.changes(change.getOriginalValues(), change.getProposedValues());
        Optional<String> closed = pending ? notDecidable(origin, event)
                .filter(why -> origin.getStatus() != EventRequestStatus.clarification_required) : Optional.empty();
        boolean internal = caller.role() == UserRole.ec || caller.role() == UserRole.ecl;
        List<ImpactItem> impactNow = pending && internal && closed.isEmpty()
                ? impact.assess(event, ChangeFields.detailsOf(change.getProposedValues()), fields(changes))
                : null;
        boolean canDecide = pending && closed.isEmpty() && caller.role() == UserRole.ec
                && caller.userId().equals(coordinatorOf(origin, event))
                && origin.getStatus() != EventRequestStatus.clarification_required;
        return new View(change.getChangeRequestId(), change.getRequestId(), origin.getEventId(),
                nameOf(origin, event), origin.getOrganisation(), currentStatus(origin, event),
                change.getStatus().name(), closed.orElse(null), change.getReason(),
                nameOf(change.getRequestedBy()), change.getRequestedAt(),
                change.getDecidedBy() == null ? null : nameOf(change.getDecidedBy()), change.getDecidedAt(),
                change.getDecisionReason(), changes, impactNow, followUps(change), canDecide,
                pending && caller.role() == UserRole.eo && sameOrganisation(caller, origin));
    }

    private static List<ImpactItem> followUps(ChangeRequest change) {
        return change.getAcknowledgedImpacts() == null ? List.of() : change.getAcknowledgedImpacts().stream()
                .map(m -> new ImpactItem(m.get("key"), m.get("area"), ChangeImpactService.ATTENTION, m.get("title"),
                        m.get("detail")))
                .toList();
    }

    private static String currentStatus(EventRequest origin, Event event) {
        return event != null ? event.getStatus().name() : origin.getStatus().name();
    }

    private String nameOf(UUID userId) {
        return users.findById(userId).map(User::getUsername).orElse("Unknown user");
    }

    private static String requiredText(String raw, String blankMessage) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new InvalidMessageException(blankMessage);
        }
        if (text.length() > MAX_TEXT) {
            throw new InvalidMessageException("This can be at most " + MAX_TEXT + " characters.");
        }
        return text;
    }

    /** A timeline entry's message holds at most MAX_TEXT characters (V13's
     * chk_activity_message_length). A reason plus the field summary can be longer,
     * so the end is cut off rather than failing the whole action. */
    static String fitActivityMessage(String message) {
        if (message.length() <= MAX_TEXT) {
            return message;
        }
        int end = MAX_TEXT - 1;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end) + "…";
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** Map.copyOf refuses null values; an empty field is simply absent. */
    private static Map<String, Object> withoutNulls(Map<String, Object> values) {
        Map<String, Object> kept = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                kept.put(key, value);
            }
        });
        return kept;
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
