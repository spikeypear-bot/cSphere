package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EquipmentReservationService {

    private final EquipmentRepository equipmentRepository;
    private final SerialisedEquipmentRepository unitRepository;
    private final EquipmentStatusPeriodRepository statusPeriodRepository;
    private final EquipmentLogRepository logRepository;
    private final EquipmentRequestLineRepository requestLineRepository;
    private final EquipmentRequestRepository requestRepository;
    private final EquipmentAvailabilityService availability;
    private final EventRepository eventRepository;

    @Autowired
    public EquipmentReservationService(
            EquipmentRepository equipmentRepository,
            SerialisedEquipmentRepository unitRepository,
            EquipmentStatusPeriodRepository statusPeriodRepository,
            EquipmentLogRepository logRepository,
            EquipmentRequestLineRepository requestLineRepository,
            EquipmentRequestRepository requestRepository,
            EquipmentAvailabilityService availability,
            EventRepository eventRepository) {
        this.equipmentRepository = equipmentRepository;
        this.unitRepository = unitRepository;
        this.statusPeriodRepository = statusPeriodRepository;
        this.logRepository = logRepository;
        this.requestLineRepository = requestLineRepository;
        this.requestRepository = requestRepository;
        this.availability = availability;
        this.eventRepository = eventRepository;
    }

    /** For callers wired from repositories alone (unit tests built before
     * D32): the shared calculator over the same repositories, and no event
     * names in reservation responses. */
    public EquipmentReservationService(
            EquipmentRepository equipmentRepository,
            SerialisedEquipmentRepository unitRepository,
            EquipmentStatusPeriodRepository statusPeriodRepository,
            EquipmentLogRepository logRepository,
            EquipmentRequestLineRepository requestLineRepository,
            EquipmentRequestRepository requestRepository) {
        this(equipmentRepository, unitRepository, statusPeriodRepository, logRepository, requestLineRepository,
                requestRepository,
                new EquipmentAvailabilityService(equipmentRepository, statusPeriodRepository, logRepository),
                null);
    }

    // AC 3 + 4: what's available, and how much, for a period.
    // Only equipment types listed in the event's equipment_request_equipments are returned (AC 2 scope).
    @Transactional(readOnly = true)
    public List<EquipmentAvailabilityResponse> availabilityForEvent(
            UUID requestId, Instant start, Instant end) {
        requireValidPeriod(start, end);

        List<Equipment> kinds = requestLineRepository.findByIdRequestId(requestId).stream()
                .map(EquipmentRequestLine::getEquipment)
                .toList();
        // D32: the shared calculation (reserved, blocked and damaged units), one set of queries.
        Map<UUID, EquipmentAvailabilityService.Availability> available = availability.forEquipment(kinds, start, end);
        return kinds.stream()
                .map(equipment -> new EquipmentAvailabilityResponse(
                        equipment.getId(), equipment.getName(),
                        Boolean.TRUE.equals(equipment.getSerialised()),
                        equipment.getQuantity(), available.get(equipment.getId()).availableQuantity()))
                .toList();
    }

    // AC 5-9: reserve equipment for an event.
    @Transactional
    public EquipmentReservationResponse reserve(ReserveEquipmentRequest req) {
        if (req == null || req.eventId() == null || req.equipmentId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventId and equipmentId are required");
        }
        requireValidPeriod(req.start(), req.end());
        if (req.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be at least 1");
        }
        // TS09: serial numbers are stored as VARCHAR(64) (V34).
        int maxSerial = SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH;
        if (req.serialNumber() != null && req.serialNumber().length() > maxSerial) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "serialNumber must be at most " + maxSerial + " characters");
        }

        Equipment equipment = equipmentRepository.findForUpdate(req.equipmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found"));
        requireRequestedForEvent(req.eventId(), req.equipmentId());

        if (req.serialNumber() != null) {
            reserveSpecificUnit(equipment, req);
        } else {
            reserveByQuantity(equipment, req);
        }

        EquipmentLog log = new EquipmentLog(
                req.eventId(), req.equipmentId(), req.quantity(),
                req.serialNumber(), req.start(), req.end());
        return EquipmentReservationResponse.from(logRepository.save(log), equipment.getName(),
                eventNames(List.of(req.eventId())).get(req.eventId()));
    }

    // AC 7: refuse a unit that is Faulty/Unavailable for any part of the period.
    // AC 8: refuse a unit already committed to an overlapping reservation.
    private void reserveSpecificUnit(Equipment equipment, ReserveEquipmentRequest req) {
        if (req.quantity() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A specific serial number reserves exactly 1 unit");
        }
        boolean unitExists = unitRepository.existsById(
                new SerialisedEquipmentId(req.equipmentId(), req.serialNumber()));
        if (!unitExists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment unit not found");
        }

        boolean blocked = statusPeriodRepository.findOverlapping(req.start(), req.end()).stream()
                .anyMatch(p -> p.getEquipmentId().equals(req.equipmentId())
                        && p.getSerialNumber().equals(req.serialNumber()));
        if (blocked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This unit is marked Faulty or Unavailable for part of that period");
        }

        boolean alreadyReserved = !logRepository.findOverlappingForUnit(
                req.equipmentId(), req.serialNumber(), req.start(), req.end()).isEmpty();
        if (alreadyReserved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This unit is already reserved for an overlapping event");
        }
    }

    // AC 6: refuse a quantity greater than what's available.
    private void reserveByQuantity(Equipment equipment, ReserveEquipmentRequest req) {
        int available = availability.forEquipment(equipment, req.start(), req.end()).availableQuantity();
        if (req.quantity() > available) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only " + available + " of " + equipment.getName() + " available for that period");
        }
    }

    private void requireRequestedForEvent(UUID eventId, UUID equipmentId) {
        // EO07 AC12: nothing more is reserved for a cancelled or finished event.
        if (requestRepository.eventClosed(eventId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This event has been cancelled or has finished, so no equipment can be reserved for it.");
        }
        boolean requested = requestRepository.findByEventIdForUpdate(eventId).stream()
                .filter(request -> request.getStatus() == EquipmentRequestStatus.processing)
                .map(EquipmentRequest::getId)
                .anyMatch(requestId -> requestLineRepository.findByIdRequestId(requestId).stream()
                        .anyMatch(line -> line.getId().getEquipmentId().equals(equipmentId)));
        if (!requested) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This equipment is not part of an active request for the selected event.");
        }
    }

    // AC 11: reservations already made for an event.
    @Transactional(readOnly = true)
    public List<EquipmentReservationResponse> reservationsForEvent(UUID eventId) {
        return toResponses(logRepository.findByEventId(eventId));
    }

    /** D32: the by-equipment twin of {@link #reservationsForEvent}: every
     * reservation of one kind overlapping [from, to), for events that are not
     * cancelled or completed (the availability query, EO07), earliest first. */
    @Transactional(readOnly = true)
    public List<EquipmentReservationResponse> reservationsForEquipment(UUID equipmentId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required, and to must be after from");
        }
        if (!equipmentRepository.existsById(equipmentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found");
        }
        return toResponses(logRepository.findOverlappingForEquipment(equipmentId, from, to).stream()
                .sorted(Comparator.comparing(EquipmentLog::getLoanedFrom).thenComparing(EquipmentLog::getId))
                .toList());
    }

    /** One mapper for both reservation lists: names are loaded once per kind and per event. */
    private List<EquipmentReservationResponse> toResponses(List<EquipmentLog> logs) {
        Map<UUID, String> equipmentNames = new HashMap<>();
        equipmentRepository.findAllById(logs.stream().map(EquipmentLog::getEquipmentId).distinct().toList())
                .forEach(equipment -> equipmentNames.put(equipment.getId(), equipment.getName()));
        Map<UUID, String> eventNames = eventNames(logs.stream().map(EquipmentLog::getEventId).distinct().toList());
        return logs.stream()
                .map(log -> EquipmentReservationResponse.from(log,
                        equipmentNames.getOrDefault(log.getEquipmentId(), "Unknown"),
                        eventNames.get(log.getEventId())))
                .toList();
    }

    private Map<UUID, String> eventNames(List<UUID> eventIds) {
        Map<UUID, String> names = new HashMap<>();
        if (eventRepository != null && !eventIds.isEmpty()) {
            eventRepository.findAllById(eventIds).forEach(event -> names.put(event.getEventId(), event.getEventName()));
        }
        return names;
    }

    private void requireValidPeriod(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end must be after start");
        }
    }
}