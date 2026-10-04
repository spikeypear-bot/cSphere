package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentRepository;
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

    public EquipmentRequestService(
            EventRepository events,
            EquipmentRequestRepository requests,
            EquipmentRequestLineRepository lines,
            EquipmentRepository equipment) {
        this.events = events;
        this.requests = requests;
        this.lines = lines;
        this.equipment = equipment;
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

    @Transactional
    public EquipmentRequestDetailsResponse submit(
            UUID coordinatorId, UUID eventId, SubmitEquipmentRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        if (event.getStatus() != EventStatus.pending) {
            throw new EquipmentRequestStateException(
                    "Equipment can only be requested while the event is in Planning.");
        }
        if (requests.findByEventId(eventId).stream()
                .anyMatch(request -> request.getStatus() != EquipmentRequestStatus.rejected)) {
            throw new EquipmentRequestStateException(
                    "This event already has an equipment request awaiting review or already approved.");
        }
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

        UUID requestId = UUID.randomUUID();
        EquipmentRequest saved = requests.save(new EquipmentRequest(
                requestId, eventId, EquipmentRequestStatus.processing, requirement));
        for (SubmitEquipmentRequest.Item item : requestedItems) {
            lines.save(new EquipmentRequestLine(
                    new EquipmentRequestLineId(requestId, item.equipmentId()), item.quantity()));
        }
        return new EquipmentRequestDetailsResponse(
                saved.getId(),
                saved.getEventId(),
                saved.getStatus().name(),
                saved.getTechnicalRequirement(),
                requestedItems.stream().map(item -> new EquipmentRequestLineResponse(
                        item.equipmentId(),
                        selectedEquipment.get(item.equipmentId()).getName(),
                        item.quantity())).toList());
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }
}
