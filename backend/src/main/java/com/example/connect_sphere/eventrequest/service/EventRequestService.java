package com.example.connect_sphere.eventrequest.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
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

    private final EventRequestRepository repository;
    private final EventRequestMapper mapper;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public EventRequestService(
            EventRequestRepository repository,
            EventRequestMapper mapper,
            EventRepository eventRepository,
            UserRepository userRepository,
            NotificationService notificationService) {
        this.repository = repository;
        this.mapper = mapper;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
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

    @Transactional
    public EventRequestDto updateDraft(String organisation, UUID requestId, SaveEventRequestRequest request) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedDraft(organisation, requestId);
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
    public EventRequestDto submit(String organisation, UUID requestId) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedDraft(organisation, requestId);
        List<String> missing = missingRequiredFields(entity);
        if (!missing.isEmpty()) {
            throw new IncompleteEventRequestException(missing);
        }
        if (entity.getEndDatetime().isBefore(entity.getStartDatetime())) {
            throw new InvalidEventRequestScheduleException();
        }
        entity.setStatus(EventRequestStatus.pending);
        entity.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDto(repository.save(entity));
    }

    // ---- Event Coordinator side (EC01/EC02 minimal slice; EO09/EO19's own
    // stories don't ask for a full review UI, only for a real transition to
    // notify off — see docs/decision-log.md) --------------------------------

    /** EC review queue: every organisation's pending requests, oldest first —
     * Coordinators are internal staff, not scoped by organisation the way an
     * Organiser is (SecurityConfig's own comment on this). */
    @Transactional(readOnly = true)
    public List<EventRequestDto> listPendingReview() {
        return repository.findByStatusOrderByCreatedAtAsc(EventRequestStatus.pending).stream()
                .map(mapper::toDto)
                .toList();
    }

    /**
     * EO19. Assigning the same coordinator again is a deliberate no-op — no
     * notification, no change — per the story's own "does not receive a
     * duplicate notification if the same Event Coordinator is saved again
     * without an actual change of assignment."
     */
    @Transactional
    public EventRequestDto assignCoordinator(UUID requestId, UUID coordinatorUserId) {
        EventRequest entity = repository.findById(requestId)
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

        if (saved.getCreatedBy() != null) {
            notificationService.createCoordinatorAssignmentNotification(
                    saved.getCreatedBy(), saved.getRequestId(), saved.getEventId(),
                    saved.getEventName(), coordinator.getUsername(), coordinator.getEmail(),
                    previousCoordinatorId != null);
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
        EventRequest entity = findPendingAssignedTo(actingCoordinatorId, requestId);

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
        EventRequest entity = findPendingAssignedTo(actingCoordinatorId, requestId);
        entity.setStatus(EventRequestStatus.rejected);
        entity.setRejectionReason(reason);
        entity.setUpdatedAt(OffsetDateTime.now());
        EventRequest saved = repository.save(entity);

        if (saved.getCreatedBy() != null) {
            notificationService.createStatusChangeNotification(
                    saved.getCreatedBy(), saved.getRequestId(), null,
                    saved.getEventName(), "rejected", reason);
        }
        return mapper.toDto(saved);
    }

    private EventRequest findPendingAssignedTo(UUID actingCoordinatorId, UUID requestId) {
        EventRequest entity = repository.findById(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (entity.getStatus() != EventRequestStatus.pending) {
            throw new EventRequestNotPendingException(requestId, entity.getStatus());
        }
        if (!actingCoordinatorId.equals(entity.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(requestId);
        }
        return entity;
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
        EventRequest entity = repository.findById(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (!organisation.equals(entity.getOrganisation())) {
            throw new EventRequestNotFoundException(requestId);
        }
        return entity;
    }

    private EventRequest findOwnedDraft(String organisation, UUID requestId) {
        EventRequest entity = findOwned(organisation, requestId);
        if (entity.getStatus() != EventRequestStatus.draft) {
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
