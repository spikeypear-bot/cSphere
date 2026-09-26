package com.example.connect_sphere.eventrequest.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.EventRequestReviewDto;
import com.example.connect_sphere.eventrequest.dto.ReviewQueueDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * Implements EO01 (save/reopen/edit a draft), EO02 (submit), and — as of the
 * EO09/EO19 slice — the minimal Event Coordinator side needed to make either
 * story's notifications real: assigning a coordinator, and approving/
 * rejecting a request. Business rules live here, not in the controller or
 * the entity, per AI_Context.md's layering convention.
 */
@Service
public class EventRequestService {

    private static final char REQUEST_TYPE_CREATION = 'C';

    /** EC01/EO26: longest clarification message or organiser response. */
    static final int MAX_MESSAGE_LENGTH = 2000;

    /** Field keys a coordinator may flag (EC01), matching
     * SaveEventRequestRequest's own keys so the organiser's form can
     * highlight each one directly. */
    static final Set<String> FLAGGABLE_FIELDS = Set.of(
            "eventName", "purpose", "description", "startDatetime", "endDatetime",
            "expectedAttendance", "venueRequirements", "equipmentRequirements",
            "accessibilityNeeds", "registrationNeeds");

    private final EventRequestRepository repository;
    private final EventRequestMapper mapper;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ActivityService activityService;

    public EventRequestService(
            EventRequestRepository repository,
            EventRequestMapper mapper,
            EventRepository eventRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            ActivityService activityService) {
        this.repository = repository;
        this.mapper = mapper;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.activityService = activityService;
    }

    @Transactional
    public EventRequestDto saveNewDraft(String organisation, UUID createdBy, SaveEventRequestRequest request) {
        requireOrganisation(organisation);
        EventRequest entity = new EventRequest();
        entity.setRequestId(UUID.randomUUID());
        entity.setRequestType(REQUEST_TYPE_CREATION);
        entity.setOrganisation(organisation);
        entity.setCreatedBy(createdBy);
        entity.setStatus(EventRequestStatus.draft);
        OffsetDateTime now = OffsetDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        applyFields(entity, request);
        return mapper.toDto(repository.save(entity));
    }

    /** EO01 draft edits, and (EO26) edits to a request that is waiting for
     * clarification. Saving never changes the status in either case: for a
     * returned request only resubmit() sends it back for review. */
    @Transactional
    public EventRequestDto updateDraft(String organisation, UUID requestId, SaveEventRequestRequest request) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedEditable(organisation, requestId);
        applyFields(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        // If this save fails (e.g. the transaction rolls back for any reason),
        // Spring/JPA leaves the previously committed row untouched — EO01's
        // "does not overwrite the last successfully saved version if a later
        // save attempt fails" falls out of @Transactional for free, as long as
        // nothing here does a non-transactional side effect.
        return mapper.toDto(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public EventRequestDto get(String organisation, UUID requestId) {
        requireOrganisation(organisation);
        return mapper.toDto(findOwned(organisation, requestId));
    }

    @Transactional(readOnly = true)
    public List<EventRequestDto> list(String organisation) {
        requireOrganisation(organisation);
        return repository.findByOrganisationOrderByCreatedAtDesc(organisation).stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public EventRequestDto submit(String organisation, UUID actingUserId, UUID requestId) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedDraft(organisation, requestId);
        requireSubmittable(entity);
        entity.setStatus(EventRequestStatus.pending);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        activityService.record(new ActivityService.Entry(saved.getRequestId(), null, ActivityType.submitted,
                actingUserId, null, null, EventRequestStatus.draft.name(), EventRequestStatus.pending.name()));
        return mapper.toDto(saved);
    }

    /**
     * EO26: the organiser answers a clarification request and sends the
     * request back for review. The same completeness and date rules as the
     * first submission apply (a returned request must not come back less
     * complete), the same coordinator stays assigned, and the response is
     * recorded on the timeline in the same transaction as the status change.
     */
    @Transactional
    public EventRequestDto resubmit(String organisation, UUID actingUserId, UUID requestId, String response) {
        return resubmit(organisation, actingUserId, requestId, response, null);
    }

    /**
     * As above, applying the organiser's edited {@code details} first, in the
     * same transaction: if any check then fails, the whole call rolls back and
     * the request is exactly as it was (EO26: "status, request details,
     * timeline and notifications are unchanged").
     */
    @Transactional
    public EventRequestDto resubmit(String organisation, UUID actingUserId, UUID requestId, String response,
            SaveEventRequestRequest details) {
        requireOrganisation(organisation);
        String reply = requireMessage(response, "Please add a short response for the Event Coordinator.");
        EventRequest entity = findOwned(organisation, requestId, true);
        if (entity.getStatus() != EventRequestStatus.clarification_required) {
            throw new EventRequestStateException(
                    "Only a request that is waiting for your clarification can be resubmitted (current status: "
                            + label(entity.getStatus()) + ").");
        }
        if (details != null) {
            // Check the edited details on a throwaway copy first, and only then
            // touch the real row: a refusal leaves the request exactly as it
            // was even when this runs inside a caller's larger transaction.
            EventRequest candidate = new EventRequest();
            applyFields(candidate, details);
            requireSubmittable(candidate);
            applyFields(entity, details);
        }
        requireSubmittable(entity);
        entity.setStatus(EventRequestStatus.pending);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        activityService.record(new ActivityService.Entry(saved.getRequestId(), null,
                ActivityType.clarification_responded, actingUserId, reply, null,
                EventRequestStatus.clarification_required.name(), EventRequestStatus.pending.name(),
                null, fieldValues(saved)));
        if (saved.getCoordinatorId() != null) {
            UUID coordinatorId = saved.getCoordinatorId();
            afterThisTransactionCommits(() -> notificationService.createClarificationRespondedNotification(
                    coordinatorId, saved.getRequestId(), saved.getEventName(), reply));
        }
        return mapper.toDto(saved);
    }

    /**
     * The whole journey of an event, from its request's submission through
     * review, clarification, approval and venue planning, for the event
     * details page. Organisers see their own organisation's events and only
     * Organiser-visible entries; coordinators see only events assigned to
     * them, with every coordinator-visible entry.
     */
    @Transactional(readOnly = true)
    public List<ActivityDto> timelineForEvent(UserRole viewerRole, String organisation, UUID viewerId, UUID eventId) {
        EventRequest origin = repository.findByEventId(eventId)
                .orElseThrow(() -> new EventRequestNotFoundException(eventId));
        if (viewerRole == UserRole.eo) {
            if (organisation == null || !organisation.equals(origin.getOrganisation())) {
                throw new EventRequestNotFoundException(eventId);
            }
        } else {
            requireAssigned(viewerId, origin);
        }
        return activityService.timeline(origin.getRequestId(), viewerRole);
    }

    /** EO26: the organiser's view of their request's timeline. Scoped by
     * organisation exactly like get(), and filtered to Organiser-visible
     * entries by the repository query. */
    @Transactional(readOnly = true)
    public List<ActivityDto> timelineForOrganiser(String organisation, UUID requestId) {
        requireOrganisation(organisation);
        EventRequest entity = findOwned(organisation, requestId);
        return activityService.timeline(entity.getRequestId(), UserRole.eo);
    }

    // ---- Event Coordinator side (EC01/EC02 minimal slice; EO09/EO19's own
    // stories don't ask for a full review UI, only for a real transition to
    // notify off — see docs/decision-log.md) --------------------------------

    /**
     * EC02 review queue for one coordinator: their submitted requests (oldest
     * first), their requests waiting on the organiser, and unassigned ones
     * they could pick up (Coordinator Assignment). Coordinators are internal
     * staff, so none of this is scoped by organisation. Requests assigned to
     * other coordinators are left out: EC02 says a coordinator "cannot view
     * or review an event request that is not assigned to them".
     */
    @Transactional(readOnly = true)
    public ReviewQueueDto reviewQueue(UUID coordinatorId) {
        return new ReviewQueueDto(
                repository.findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(coordinatorId, EventRequestStatus.pending)
                        .stream().map(mapper::toDto).toList(),
                repository.findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(
                        coordinatorId, EventRequestStatus.clarification_required)
                        .stream().map(mapper::toDto).toList(),
                repository.findByStatusAndCoordinatorIdIsNullOrderByCreatedAtAsc(EventRequestStatus.pending)
                        .stream().map(mapper::toDto).toList());
    }

    /**
     * EC02 review screen. Only the assigned coordinator may open it. It
     * reports what would block approval *now* (missing fields, schedule), so
     * the page can explain a disabled Approve button before anyone presses
     * it, and includes the full coordinator-visible timeline.
     */
    @Transactional(readOnly = true)
    public EventRequestReviewDto getForReview(UUID coordinatorId, UUID requestId) {
        EventRequest entity = repository.findById(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        requireAssigned(coordinatorId, entity);
        return new EventRequestReviewDto(
                mapper.toDto(entity),
                missingRequiredFields(entity),
                scheduleValid(entity),
                activityService.timeline(requestId, UserRole.ec));
    }

    /**
     * EC01. Only on a Submitted request assigned to the caller. The message
     * and flags are validated before anything is written; the status change
     * and its timeline entry are one transaction, so a failure leaves
     * neither behind. Organisers are notified only after that commits. The
     * organiser's submitted details are not touched.
     */
    @Transactional
    public EventRequestDto requestClarification(
            UUID coordinatorId, UUID requestId, String message, List<String> flaggedFields) {
        return requestClarification(coordinatorId, requestId, message, flaggedFields, null);
    }

    /** As above, with (V14) one question per flagged field. When given, every
     * flagged field needs a question and no other field may have one. */
    @Transactional
    public EventRequestDto requestClarification(UUID coordinatorId, UUID requestId, String message,
            List<String> flaggedFields, Map<String, String> fieldQuestions) {
        String text = requireMessage(message, "Please describe what needs clarifying.");
        List<String> flags = normaliseFlags(flaggedFields);
        Map<String, String> questions = normaliseQuestions(flags, fieldQuestions);
        EventRequest entity = repository.findForUpdate(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        requireAssigned(coordinatorId, entity);
        if (entity.getStatus() != EventRequestStatus.pending) {
            throw new EventRequestStateException(entity.getStatus() == EventRequestStatus.clarification_required
                    ? "Clarification has already been requested. Wait for the organiser to respond before asking again."
                    : "Clarification can only be requested on a submitted request (current status: "
                            + label(entity.getStatus()) + ").");
        }
        entity.setStatus(EventRequestStatus.clarification_required);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        activityService.record(new ActivityService.Entry(saved.getRequestId(), null,
                ActivityType.clarification_requested, coordinatorId, text, flags,
                EventRequestStatus.pending.name(), EventRequestStatus.clarification_required.name(),
                questions, fieldValues(saved)));

        List<UUID> organisers = userRepository.findByRoleAndOrganisation(UserRole.eo, saved.getOrganisation())
                .stream().map(User::getUserId).toList();
        if (!organisers.isEmpty()) {
            afterThisTransactionCommits(() -> notificationService.createClarificationRequestedNotifications(
                    organisers, saved.getRequestId(), saved.getEventName(), text));
        }
        return mapper.toDto(saved);
    }

    /**
     * EO19. Assigning the same coordinator again is a deliberate no-op — no
     * notification, no change — per the story's own "does not receive a
     * duplicate notification if the same Event Coordinator is saved again
     * without an actual change of assignment."
     */
    @Transactional
    public EventRequestDto assignCoordinator(UUID requestId, UUID coordinatorUserId) {
        EventRequest entity = repository.findForUpdate(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (coordinatorUserId == null) {
            throw new InvalidCoordinatorException();
        }

        // Checked before the coordinator lookup below, not after: a true
        // no-op (re-saving the coordinator already assigned) should short-
        // circuit without even querying whether that id is still a valid EC,
        // matching "does not receive a duplicate notification ... without an
        // actual change of assignment" as an actual no-op, not just a
        // suppressed notification.
        UUID previousCoordinatorId = entity.getCoordinatorId();
        if (coordinatorUserId.equals(previousCoordinatorId)) {
            return mapper.toDto(entity);
        }

        User coordinator = userRepository.findById(coordinatorUserId)
                .filter(u -> u.getRole() == UserRole.ec)
                .orElseThrow(() -> new InvalidCoordinatorException(coordinatorUserId));

        entity.setCoordinatorId(coordinatorUserId);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        // Self-assignment is the only assignment flow the UI offers, so the
        // coordinator being assigned is also who performed it.
        activityService.record(new ActivityService.Entry(saved.getRequestId(), saved.getEventId(),
                ActivityType.coordinator_assigned, coordinatorUserId,
                previousCoordinatorId != null ? "Reassigned to " + coordinator.getUsername() : null,
                null, null, null));

        if (saved.getCreatedBy() != null) {
            // After commit, like approve(): the notification is written in its
            // own transaction, and must neither reference an uncommitted row
            // nor announce an assignment that is then rolled back.
            boolean reassignment = previousCoordinatorId != null;
            afterThisTransactionCommits(() -> notificationService.createCoordinatorAssignmentNotification(
                    saved.getCreatedBy(), saved.getRequestId(), saved.getEventId(),
                    saved.getEventName(), coordinator.getUsername(), coordinator.getEmail(),
                    reassignment));
        }
        return mapper.toDto(saved);
    }

    /**
     * EO09 "Approved". Creates the {@link Event} this request has always
     * pointed towards conceptually (SCHEMA.md §1: "on approval the request
     * becomes an event") and links the two by setting this request's
     * `event_id`, purely for traceability — it does not change {@code
     * request_type} or retroactively make this a change request (D7a).
     */
    @Transactional
    public EventRequestDto approve(UUID actingCoordinatorId, UUID requestId) {
        EventRequest entity = findDecidableAssignedTo(actingCoordinatorId, requestId);
        if (entity.getStatus() == EventRequestStatus.clarification_required) {
            throw new EventRequestStateException(
                    "This request is waiting for the organiser's clarification. It can be approved once they resubmit.");
        }
        // EC02: re-check the submission rules before planning starts, rather
        // than trusting that nothing has changed since submission.
        requireSubmittable(entity);

        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName(entity.getEventName());
        event.setPurpose(entity.getPurpose());
        event.setDescription(entity.getDescription());
        event.setStartDatetime(entity.getStartDatetime());
        event.setEndDatetime(entity.getEndDatetime());
        event.setExpectedAttendance(entity.getExpectedAttendance());
        event.setVenueRequirements(entity.getVenueRequirements());
        event.setEquipmentRequirements(entity.getEquipmentRequirements());
        event.setAccessibilityNeeds(entity.getAccessibilityNeeds().stream().map(Enum::name).toList());
        event.setRegistrationNeeds(entity.getRegistrationNeeds());
        event.setOrganisation(entity.getOrganisation());
        event.setCoordinatorId(actingCoordinatorId);
        event.setStatus(EventStatus.pending);
        Event savedEvent = eventRepository.save(event);

        entity.setStatus(EventRequestStatus.approved);
        entity.setEventId(savedEvent.getEventId());
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        activityService.record(new ActivityService.Entry(saved.getRequestId(), savedEvent.getEventId(),
                ActivityType.approved, actingCoordinatorId, null, null,
                EventRequestStatus.pending.name(), EventRequestStatus.approved.name()));

        if (saved.getCreatedBy() != null) {
            // The Event row saved above is not yet committed/durable in this
            // still-open transaction. createStatusChangeNotification runs in
            // its own REQUIRES_NEW transaction (a separate connection), which
            // cannot see it yet — the notification's FK to events would fail.
            // Deferring to afterCommit ensures the referenced event exists by
            // the time the notification is written.
            afterThisTransactionCommits(() -> notificationService.createStatusChangeNotification(
                    saved.getCreatedBy(), saved.getRequestId(), savedEvent.getEventId(),
                    saved.getEventName(), "approved", null));
        }
        return mapper.toDto(saved);
    }

    /**
     * Runs {@code action} once the currently active transaction commits, or
     * immediately if there is none (e.g. a unit test calling this service
     * method directly, bypassing the Spring transaction proxy).
     */
    private void afterThisTransactionCommits(Runnable action) {
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

    /** EO09 "Rejected". */
    @Transactional
    public EventRequestDto reject(UUID actingCoordinatorId, UUID requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new MissingRejectionReasonException();
        }
        EventRequest entity = findDecidableAssignedTo(actingCoordinatorId, requestId);
        EventRequestStatus previous = entity.getStatus();
        entity.setStatus(EventRequestStatus.rejected);
        entity.setRejectionReason(reason);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);
        activityService.record(new ActivityService.Entry(saved.getRequestId(), null,
                ActivityType.rejected, actingCoordinatorId, reason, null,
                previous.name(), EventRequestStatus.rejected.name()));

        if (saved.getCreatedBy() != null) {
            afterThisTransactionCommits(() -> notificationService.createStatusChangeNotification(
                    saved.getCreatedBy(), saved.getRequestId(), null,
                    saved.getEventName(), "rejected", reason));
        }
        return mapper.toDto(saved);
    }

    /**
     * A request the caller may approve or reject: assigned to them, and
     * Submitted or Clarification Required. Rejecting while waiting on the
     * organiser is allowed (EC01: e.g. no response); approve() then refuses
     * the second case itself with its own message. Assignment is checked
     * first so a coordinator who isn't assigned learns nothing about the
     * request's state.
     */
    private EventRequest findDecidableAssignedTo(UUID actingCoordinatorId, UUID requestId) {
        EventRequest entity = repository.findForUpdate(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        requireAssigned(actingCoordinatorId, entity);
        if (entity.getStatus() != EventRequestStatus.pending
                && entity.getStatus() != EventRequestStatus.clarification_required) {
            throw new EventRequestNotPendingException(requestId, entity.getStatus());
        }
        return entity;
    }

    private static void requireAssigned(UUID coordinatorId, EventRequest entity) {
        if (coordinatorId == null || !coordinatorId.equals(entity.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(entity.getRequestId());
        }
    }

    /** EO02's submission rules, shared by submit, resubmit and approve. */
    private static void requireSubmittable(EventRequest entity) {
        List<String> missing = missingRequiredFields(entity);
        if (!missing.isEmpty()) {
            throw new IncompleteEventRequestException(missing);
        }
        if (!scheduleValid(entity)) {
            throw new InvalidEventRequestScheduleException();
        }
    }

    private static boolean scheduleValid(EventRequest entity) {
        return entity.getStartDatetime() == null || entity.getEndDatetime() == null
                || !entity.getEndDatetime().isBefore(entity.getStartDatetime());
    }

    /** Trimmed, 1-2000 characters, or an InvalidMessageException carrying a
     * message fit to show the user. */
    private static String requireMessage(String raw, String blankMessage) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new InvalidMessageException(blankMessage);
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw new InvalidMessageException(
                    "Messages can be at most " + MAX_MESSAGE_LENGTH + " characters (this one is " + text.length() + ").");
        }
        return text;
    }

    /** Trimmed questions keyed by flagged field, or null when none were sent. */
    private static Map<String, String> normaliseQuestions(List<String> flags, Map<String, String> fieldQuestions) {
        if (fieldQuestions == null || fieldQuestions.isEmpty()) {
            return null;
        }
        if (!flags.containsAll(fieldQuestions.keySet())) {
            throw new InvalidMessageException("Questions can only be asked about flagged fields.");
        }
        Map<String, String> questions = new LinkedHashMap<>();
        for (String field : flags) {
            questions.put(field, requireMessage(fieldQuestions.get(field),
                    "Every flagged field needs a question (missing: " + field + ")."));
        }
        return questions;
    }

    /** The request's field values right now, for the timeline (V14). Plain
     * JSON values: text, numbers, booleans, ISO date-times, lists of names. */
    private static Map<String, Object> fieldValues(EventRequest e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eventName", e.getEventName());
        values.put("purpose", e.getPurpose());
        values.put("description", e.getDescription());
        values.put("startDatetime", e.getStartDatetime() == null ? null : e.getStartDatetime().toString());
        values.put("endDatetime", e.getEndDatetime() == null ? null : e.getEndDatetime().toString());
        values.put("expectedAttendance", e.getExpectedAttendance());
        values.put("venueRequirements", e.getVenueRequirements());
        values.put("equipmentRequirements", e.getEquipmentRequirements());
        values.put("accessibilityNeeds", e.getAccessibilityNeeds() == null ? List.of()
                : e.getAccessibilityNeeds().stream().map(Enum::name).toList());
        values.put("registrationNeeds", e.getRegistrationNeeds());
        return values;
    }

    /** De-duplicated, order-preserving, and only real field keys. */
    private static List<String> normaliseFlags(List<String> flaggedFields) {
        if (flaggedFields == null) {
            return List.of();
        }
        List<String> unknown = flaggedFields.stream().filter(f -> !FLAGGABLE_FIELDS.contains(f)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidMessageException("Unknown field(s) flagged: " + String.join(", ", unknown));
        }
        return flaggedFields.stream().distinct().toList();
    }

    /** The label a user sees for a status, for messages that name it. */
    private static String label(EventRequestStatus status) {
        return switch (status) {
            case draft -> "Draft";
            case pending -> "Submitted";
            case clarification_required -> "Clarification Required";
            case approved -> "Approved";
            case rejected -> "Rejected";
            case cancelled -> "Cancelled";
        };
    }

    private void applyFields(EventRequest entity, SaveEventRequestRequest request) {
        entity.setEventName(request.eventName());
        entity.setPurpose(request.purpose());
        entity.setDescription(request.description());
        entity.setStartDatetime(request.startDatetime());
        entity.setEndDatetime(request.endDatetime());
        entity.setExpectedAttendance(request.expectedAttendance());
        entity.setVenueRequirements(request.venueRequirements());
        entity.setEquipmentRequirements(request.equipmentRequirements());
        entity.setAccessibilityNeeds(
                request.accessibilityNeeds() == null ? List.of() : request.accessibilityNeeds());
        entity.setRegistrationNeeds(request.registrationNeeds());
    }

    private EventRequest findOwned(String organisation, UUID requestId) {
        return findOwned(organisation, requestId, false);
    }

    /** {@code forUpdate} takes the row lock, for callers about to change it. */
    private EventRequest findOwned(String organisation, UUID requestId, boolean forUpdate) {
        EventRequest entity = (forUpdate ? repository.findForUpdate(requestId) : repository.findById(requestId))
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (!organisation.equals(entity.getOrganisation())) {
            throw new EventRequestNotFoundException(requestId);
        }
        return entity;
    }

    private EventRequest findOwnedDraft(String organisation, UUID requestId) {
        EventRequest entity = findOwned(organisation, requestId, true);
        if (entity.getStatus() != EventRequestStatus.draft) {
            throw new EventRequestNotEditableException(requestId, entity.getStatus());
        }
        return entity;
    }

    /** Draft (EO01), or returned for clarification (EO26). */
    private EventRequest findOwnedEditable(String organisation, UUID requestId) {
        EventRequest entity = findOwned(organisation, requestId, true);
        if (entity.getStatus() != EventRequestStatus.draft
                && entity.getStatus() != EventRequestStatus.clarification_required) {
            throw new EventRequestNotEditableException(requestId, entity.getStatus());
        }
        return entity;
    }

    private static List<String> missingRequiredFields(EventRequest entity) {
        List<String> missing = new ArrayList<>();
        if (isBlank(entity.getEventName())) {
            missing.add("eventName");
        }
        if (isBlank(entity.getPurpose())) {
            missing.add("purpose");
        }
        if (entity.getStartDatetime() == null) {
            missing.add("startDatetime");
        }
        if (entity.getEndDatetime() == null) {
            missing.add("endDatetime");
        }
        if (entity.getExpectedAttendance() == null) {
            missing.add("expectedAttendance");
        }
        if (isBlank(entity.getVenueRequirements())) {
            missing.add("venueRequirements");
        }
        if (entity.getAccessibilityNeeds() == null || entity.getAccessibilityNeeds().isEmpty()) {
            missing.add("accessibilityNeeds");
        }
        return missing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireOrganisation(String organisation) {
        if (isBlank(organisation)) {
            throw new MissingOrganisationException();
        }
    }
}
