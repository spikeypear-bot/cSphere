package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipmentRequestService {

    private static final int MAX_REQUIREMENT_LENGTH = 2000;

    private final EventRepository events;
    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final EquipmentRepository equipment;
    private final EquipmentReservationService reservations;

    /** EC-NEW5: announces changes for the event change log (ChangeLogRecorder).
     * Setter-injected so this service does not depend on the log; absent in
     * plain unit tests, where nothing is announced. */
    private org.springframework.context.ApplicationEventPublisher changeLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChangeLog(org.springframework.context.ApplicationEventPublisher changeLog) {
        this.changeLog = changeLog;
    }

    /** TS09: open equipment shortfalls. Setter-injected like the change log;
     * absent in plain unit tests, where no request is for a shortfall. */
    private com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setShortfalls(com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls) {
        this.shortfalls = shortfalls;
    }

    private void announce(UUID eventId, com.example.connect_sphere.activity.entity.ActivityType type, UUID actor,
            String message) {
        if (changeLog != null) {
            changeLog.publishEvent(new com.example.connect_sphere.activity.service.EventChange(eventId, type, actor, message));
        }
    }

    public EquipmentRequestService(
            EventRepository events,
            EquipmentRequestRepository requests,
            EquipmentRequestLineRepository lines,
            EquipmentRepository equipment,
            EquipmentReservationService reservations) {
        this.events = events;
        this.requests = requests;
        this.lines = lines;
        this.equipment = equipment;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public List<EquipmentRequestDetailsResponse> listForEvent(UUID coordinatorId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        return requests.findByEventId(eventId).stream()
                .map(request -> EquipmentRequestDetailsResponse.from(
                        request, lines.findByIdRequestId(request.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EquipmentRequestDetailsResponse> listForSafetyOfficer(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!EventStatus.ACTIVE.contains(event.getStatus())) {
            throw new EventNotFoundException(eventId);
        }
        return requests.findByEventId(eventId).stream()
                .map(request -> EquipmentRequestDetailsResponse.from(
                        request, lines.findByIdRequestId(request.getId())))
                .toList();
    }

    @Transactional
    public EquipmentRequestDetailsResponse submit(
            UUID coordinatorId, UUID eventId, SubmitEquipmentRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        if (!event.getStatus().isPlanningEditable()) {
            throw new EquipmentRequestStateException(
                    "Equipment can only be requested while the event is in Planning.");
        }
        // TS09 AC19, AC21: an open equipment shortfall allows an additional
        // request alongside the approved one, but never a second one awaiting
        // review. Without one, EC07's rule applies unchanged.
        com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome shortfall = null;
        if (input != null && input.shortfallOutcomeId() != null && shortfalls != null) {
            shortfall = shortfalls.requireOpenShortfall(eventId, input.shortfallOutcomeId());
            if (requests.findByEventId(eventId).stream()
                    .anyMatch(request -> request.getStatus() == EquipmentRequestStatus.processing)) {
                throw new EquipmentRequestStateException(
                        "This event already has an equipment request awaiting review.");
            }
        } else if (requests.findByEventId(eventId).stream()
                .anyMatch(request -> request.getStatus() != EquipmentRequestStatus.rejected)) {
            throw new EquipmentRequestStateException(
                    "This event already has an equipment request awaiting review or already approved.");
        }
        ValidatedItems validated = validate(input);

        UUID requestId = UUID.randomUUID();
        EquipmentRequest saved = requests.save(new EquipmentRequest(
                requestId, eventId, EquipmentRequestStatus.processing, validated.requirement()));
        for (SubmitEquipmentRequest.Item item : validated.items()) {
            lines.save(new EquipmentRequestLine(
                    new EquipmentRequestLineId(requestId, item.equipmentId()), item.quantity()));
        }
        if (shortfall != null) {
            shortfalls.linkAdditionalRequest(shortfall, requestId);
        }
        int units = validated.items().stream().mapToInt(SubmitEquipmentRequest.Item::quantity).sum();
        announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.equipment_requested, coordinatorId,
                "Requested " + units + (units == 1 ? " item" : " items") + " of equipment"
                        + (validated.requirement().isBlank() ? "" : ": " + validated.requirement()));
        return responseOf(saved, validated);
    }

    /**
     * EC-NEW3 (D36, Approach 3 "supersede and rebuild"): the coordinator
     * replaces the complete picture of what the event needs — in either
     * direction — after judging, from an approved change, that the
     * equipment requested no longer fits. Every reservation the event holds
     * is released (a reservation names only its event, never a request) and
     * every non-rejected request for the event is superseded, then one new
     * processing request is created from {@code input}, exactly as
     * {@link #submit} would create a first one.
     */
    @Transactional
    public EquipmentRequestDetailsResponse revise(
            UUID coordinatorId, UUID eventId, SubmitEquipmentRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        if (requests.eventClosed(eventId)) {
            throw new EquipmentRequestStateException(
                    "This event has been cancelled or has finished, so its equipment request can no longer be revised.");
        }
        List<EquipmentRequest> active = requests.findByEventIdForUpdate(eventId).stream()
                .filter(request -> request.getStatus() == EquipmentRequestStatus.processing
                        || request.getStatus().heldByTechnicalSupport())
                .toList();
        if (active.isEmpty()) {
            throw new EquipmentRequestStateException(
                    "This event has no equipment request to revise. Submit a new request instead.");
        }
        ValidatedItems validated = validate(input);

        reservations.releaseAllForEvent(eventId);
        active.forEach(EquipmentRequest::supersede);
        requests.saveAll(active);

        UUID requestId = UUID.randomUUID();
        EquipmentRequest saved = requests.save(new EquipmentRequest(
                requestId, eventId, EquipmentRequestStatus.processing, validated.requirement()));
        for (SubmitEquipmentRequest.Item item : validated.items()) {
            lines.save(new EquipmentRequestLine(
                    new EquipmentRequestLineId(requestId, item.equipmentId()), item.quantity()));
        }
        int units = validated.items().stream().mapToInt(SubmitEquipmentRequest.Item::quantity).sum();
        announce(eventId, com.example.connect_sphere.activity.entity.ActivityType.equipment_revised, coordinatorId,
                "Revised the equipment request: now " + units + (units == 1 ? " item" : " items")
                        + " of equipment, replacing the previous request"
                        + (validated.requirement().isBlank() ? "" : ": " + validated.requirement()));
        return responseOf(saved, validated);
    }

    /** The shared rules {@link #submit} and {@link #revise} both apply to
     * their new items: at least one item or a note, each item valid and
     * named once, every equipment type real. */
    private ValidatedItems validate(SubmitEquipmentRequest input) {
        if (input == null) {
            throw new InvalidEquipmentRequestException("Provide equipment details before submitting.");
        }
        String requirement = input.technicalRequirement() == null
                ? "" : input.technicalRequirement().strip();
        if (requirement.length() > MAX_REQUIREMENT_LENGTH) {
            throw new InvalidEquipmentRequestException(
                    "Technical requirements must be 2000 characters or fewer.");
        }

        List<SubmitEquipmentRequest.Item> requestedItems =
                input.items() == null ? List.of() : input.items();
        if (requirement.isBlank() && requestedItems.isEmpty()) {
            throw new InvalidEquipmentRequestException(
                    "Add at least one equipment item or describe the technical requirements.");
        }
        var ids = new HashSet<UUID>();
        for (SubmitEquipmentRequest.Item item : requestedItems) {
            if (item == null || item.equipmentId() == null || item.quantity() < 1) {
                throw new InvalidEquipmentRequestException(
                        "Each equipment item must have a valid selection and a quantity greater than zero.");
            }
            if (!ids.add(item.equipmentId())) {
                throw new InvalidEquipmentRequestException(
                        "Select each equipment type only once and adjust its quantity.");
            }
        }

        Map<UUID, Equipment> selectedEquipment = equipment.findAllById(ids).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        if (selectedEquipment.size() != ids.size()) {
            throw new InvalidEquipmentRequestException("One or more selected equipment types no longer exist.");
        }
        return new ValidatedItems(requirement, requestedItems, selectedEquipment);
    }

    private static EquipmentRequestDetailsResponse responseOf(EquipmentRequest saved, ValidatedItems validated) {
        return new EquipmentRequestDetailsResponse(
                saved.getId(),
                saved.getEventId(),
                saved.getStatus().name(),
                saved.getTechnicalRequirement(),
                saved.getRejectReason(),
                saved.getAttentionReason(),
                validated.items().stream().map(item -> new EquipmentRequestLineResponse(
                        item.equipmentId(),
                        validated.selectedEquipment().get(item.equipmentId()).getName(),
                        item.quantity())).toList());
    }

    private record ValidatedItems(
            String requirement,
            List<SubmitEquipmentRequest.Item> items,
            Map<UUID, Equipment> selectedEquipment) {
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }
}
