package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger;
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
    private final EquipmentLogRepository logRepository;
    private final EquipmentRequestLineRepository requestLineRepository;
    private final EquipmentRequestRepository requestRepository;
    private final EquipmentAvailabilityService availability;
    private final EventRepository eventRepository;

    /** TS09: what the event's approved requests are still owed, so an
     * additional request's cap (D33) counts them too, as TS06 does.
     * Setter-injected like {@code EquipmentRequestDecisionService}'s; absent
     * in plain unit tests (one request, no shortfall). */
    private EquipmentShortfallLedger shortfalls;

    @Autowired
    public EquipmentReservationService(
            EquipmentRepository equipmentRepository,
            SerialisedEquipmentRepository unitRepository,
            EquipmentLogRepository logRepository,
            EquipmentRequestLineRepository requestLineRepository,
            EquipmentRequestRepository requestRepository,
            EquipmentAvailabilityService availability,
            EventRepository eventRepository) {
        this.equipmentRepository = equipmentRepository;
        this.unitRepository = unitRepository;
        this.logRepository = logRepository;
        this.requestLineRepository = requestLineRepository;
        this.requestRepository = requestRepository;
        this.availability = availability;
        this.eventRepository = eventRepository;
    }

    @Autowired(required = false)
    void setShortfalls(EquipmentShortfallLedger shortfalls) {
        this.shortfalls = shortfalls;
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

    /**
     * AC 5-9: reserve equipment for an event. One item, through the same
     * per-item checks as {@link #reserveBatch}. A serialised kind reserved
     * without a serial number gets free, working units picked by the server,
     * one reservation per unit (D33); the response is the first of them.
     */
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
        requireSerialLength(req.serialNumber(), req.equipmentId());

        Equipment equipment = equipmentRepository.findForUpdate(req.equipmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found"));
        requireEventOpen(req.eventId());

        if (req.serialNumber() != null && req.quantity() != 1) {
            requireRequestedForEvent(req.eventId(), req.equipmentId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A specific serial number reserves exactly 1 unit");
        }
        boolean spare = Boolean.TRUE.equals(req.spare());
        List<EquipmentLog> logs = reserveLine(req.eventId(), equipment,
                spare ? 0 : req.quantity(), spare ? req.quantity() : 0,
                req.serialNumber() == null ? null : List.of(req.serialNumber()),
                req.start(), req.end());
        List<EquipmentLog> saved = logs.stream().map(logRepository::save).toList();
        return EquipmentReservationResponse.from(saved.get(0), equipment.getName(),
                eventNames(List.of(req.eventId())).get(req.eventId()));
    }

    /**
     * D33: reserve several of an event's requested items for one window in
     * one transaction. Every line goes through {@link #reserveLine}'s checks
     * before anything is written, so a refused line saves nothing. The error
     * names the refused line's {@code equipmentId}.
     */
    @Transactional
    public List<EquipmentReservationResponse> reserveBatch(ReserveBatchRequest req) {
        if (req == null || req.eventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
        }
        requireValidPeriod(req.start(), req.end());
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one item to reserve");
        }
        Set<UUID> seen = new HashSet<>();
        for (ReserveBatchRequest.Line line : req.lines()) {
            if (line == null || line.equipmentId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line needs an equipmentId");
            }
            UUID id = line.equipmentId();
            if (!seen.add(id)) {
                throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                        "Each item can appear only once in a reservation", id);
            }
            int quantity = line.quantity() == null ? 0 : line.quantity();
            int spare = line.spare() == null ? 0 : line.spare();
            if (quantity < 0 || spare < 0 || quantity + spare < 1) {
                throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                        "Reserve at least 1 unit of each item, and no negative quantities", id);
            }
            if (line.serialNumbers() != null) {
                for (String serial : line.serialNumbers()) {
                    if (serial == null || serial.isBlank()) {
                        throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                                "Serial numbers cannot be blank", id);
                    }
                    requireSerialLength(serial, id);
                }
            }
        }

        // Lock every kind first, in one order, so two batches cannot deadlock.
        Map<UUID, Equipment> kinds = new HashMap<>();
        for (UUID id : seen.stream().sorted().toList()) {
            kinds.put(id, equipmentRepository.findForUpdate(id)
                    .orElseThrow(() -> new ReservationLineException(HttpStatus.NOT_FOUND, "Equipment not found", id)));
        }
        requireEventOpen(req.eventId());

        List<EquipmentLog> logs = new ArrayList<>();
        for (ReserveBatchRequest.Line line : req.lines()) {
            logs.addAll(reserveLine(req.eventId(), kinds.get(line.equipmentId()),
                    line.quantity() == null ? 0 : line.quantity(), line.spare() == null ? 0 : line.spare(),
                    line.serialNumbers(), req.start(), req.end()));
        }
        String eventName = eventNames(List.of(req.eventId())).get(req.eventId());
        Map<UUID, String> names = new LinkedHashMap<>();
        kinds.values().forEach(kind -> names.put(kind.getId(), kind.getName()));
        return logs.stream().map(logRepository::save)
                .map(log -> EquipmentReservationResponse.from(log, names.get(log.getEquipmentId()), eventName))
                .toList();
    }

    /**
     * The checks every reservation of one kind passes (TS02 AC5-9, D33), with
     * the kind's row already locked. Builds, but does not save, the rows:
     * {@code quantity} units towards the request and {@code spare} extra
     * units marked spare. A serialised kind is one row per unit.
     */
    private List<EquipmentLog> reserveLine(UUID eventId, Equipment equipment, int quantity, int spare,
            List<String> serialNumbers, Instant start, Instant end) {
        UUID id = equipment.getId();
        int processing = requireRequestedForEvent(eventId, id);
        int units = quantity + spare;
        boolean serialised = Boolean.TRUE.equals(equipment.getSerialised());

        List<String> serials;
        if (serialNumbers != null) {
            if (!serialised) {
                throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                        equipment.getName() + " is not tracked by serial number", id);
            }
            if (serialNumbers.size() != units) {
                throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                        "Give one serial number per unit: " + units + " units but "
                                + serialNumbers.size() + " serial numbers", id);
            }
            if (new HashSet<>(serialNumbers).size() != serialNumbers.size()) {
                throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                        "Each unit can be reserved only once", id);
            }
            for (String serial : serialNumbers) {
                requireUnitFree(id, serial, start, end);
            }
            serials = serialNumbers;
        } else {
            serials = null;
        }

        // AC 6: never more than the shared availability calculation allows (D32).
        int available = availability.forEquipment(equipment, start, end).availableQuantity();
        if (units > available) {
            throw new ReservationLineException(HttpStatus.CONFLICT,
                    "Only " + available + " of " + equipment.getName() + " available for that period", id);
        }

        // D33: not above what the event asked for, unless marked spare.
        if (quantity > 0) {
            // What the event asks for: its processing request's line, plus what its
            // approved requests are still owed (TS09), as TS06 counts it.
            int requested = processing + (shortfalls == null ? 0 : shortfalls.need(eventId, id, null));
            int reserved = logRepository.findByEventId(eventId).stream()
                    .filter(log -> log.getEquipmentId().equals(id) && !log.isSpare())
                    .filter(log -> log.getLoanedFrom().isBefore(end) && log.getLoanedUntil().isAfter(start))
                    .mapToInt(EquipmentLog::getQuantity)
                    .sum();
            int allowed = Math.max(0, requested - reserved);
            if (quantity > allowed) {
                throw new ReservationLineException(HttpStatus.CONFLICT,
                        "Only " + allowed + " more " + equipment.getName() + " can be reserved for this event: "
                                + requested + " requested and " + reserved
                                + " already reserved. Mark any extra units as spare.", id);
            }
        }

        if (serialised && serials == null) {
            serials = pickFreeUnits(equipment, units, start, end);
        }
        List<EquipmentLog> logs = new ArrayList<>();
        if (serialised) {
            for (int i = 0; i < serials.size(); i++) {
                logs.add(new EquipmentLog(eventId, id, 1, serials.get(i), start, end, i >= quantity));
            }
        } else {
            if (quantity > 0) {
                logs.add(new EquipmentLog(eventId, id, quantity, null, start, end, false));
            }
            if (spare > 0) {
                logs.add(new EquipmentLog(eventId, id, spare, null, start, end, true));
            }
        }
        return logs;
    }

    // AC 7: refuse a unit that is Faulty/Unavailable for any part of the period.
    // AC 8: refuse a unit already committed to an overlapping reservation.
    // D35: refuse a unit with an open damage report (TS09) for part of the period.
    private void requireUnitFree(UUID equipmentId, String serialNumber, Instant start, Instant end) {
        boolean unitExists = unitRepository.existsById(new SerialisedEquipmentId(equipmentId, serialNumber));
        if (!unitExists) {
            throw new ReservationLineException(HttpStatus.NOT_FOUND, "Equipment unit not found", equipmentId);
        }
        availability.unitConflict(equipmentId, serialNumber, start, end).ifPresent(conflict -> {
            throw new ReservationLineException(HttpStatus.CONFLICT, switch (conflict) {
                case BLOCKED -> "This unit is marked Faulty or Unavailable for part of that period";
                case RESERVED -> "This unit is already reserved for an overlapping event";
                case DAMAGED -> "This unit has been reported damaged for part of that period";
            }, equipmentId);
        });
    }

    /** D33: free, working units of a serialised kind for the whole window,
     * by the same rule as {@link #requireUnitFree} (D35), the first
     * {@code count} by serial number. */
    private List<String> pickFreeUnits(Equipment equipment, int count, Instant start, Instant end) {
        UUID id = equipment.getId();
        Set<String> taken = availability.unitsNotFree(id, start, end);
        List<String> free = unitRepository.findByIdEquipmentIdOrderByIdSerialNumberAsc(id).stream()
                .map(unit -> unit.getId().getSerialNumber())
                .filter(serial -> !taken.contains(serial))
                .sorted()
                .toList();
        if (free.size() < count) {
            throw new ReservationLineException(HttpStatus.CONFLICT,
                    "Only " + free.size() + " working " + equipment.getName()
                            + " units are free for that period", id);
        }
        return free.subList(0, count);
    }

    // EO07 AC12: nothing more is reserved for a cancelled or finished event.
    private void requireEventOpen(UUID eventId) {
        if (requestRepository.eventClosed(eventId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This event has been cancelled or has finished, so no equipment can be reserved for it.");
        }
    }

    /** The quantity of this kind on the event's processing request; 409 when
     * it is not on one (0 means not requested: a line asks for at least 1). */
    private int requireRequestedForEvent(UUID eventId, UUID equipmentId) {
        int requested = requestRepository.findByEventIdForUpdate(eventId).stream()
                .filter(request -> request.getStatus() == EquipmentRequestStatus.processing)
                .flatMap(request -> requestLineRepository.findByIdRequestId(request.getId()).stream())
                .filter(line -> line.getId().getEquipmentId().equals(equipmentId))
                .mapToInt(EquipmentRequestLine::getQuantity)
                .sum();
        if (requested == 0) {
            throw new ReservationLineException(HttpStatus.CONFLICT,
                    "This equipment is not part of an active request for the selected event.", equipmentId);
        }
        return requested;
    }

    // TS09: serial numbers are stored as VARCHAR(64) (V37).
    private void requireSerialLength(String serialNumber, UUID equipmentId) {
        int maxSerial = SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH;
        if (serialNumber != null && serialNumber.length() > maxSerial) {
            throw new ReservationLineException(HttpStatus.BAD_REQUEST,
                    "serialNumber must be at most " + maxSerial + " characters", equipmentId);
        }
    }

    // AC 11: reservations already made for an event.
    @Transactional(readOnly = true)
    public List<EquipmentReservationResponse> reservationsForEvent(UUID eventId) {
        return toResponses(logRepository.findByEventId(eventId));
    }

    /** EC-NEW3 (D36): a coordinator's complete revision of an event's
     * equipment request releases every reservation the event holds, not one
     * request's, since a reservation names only its event (TS02), never a
     * request. Hard-deleted, as TS09's covered/short outcomes already do
     * (D35) — the superseded request itself is kept for the audit trail. */
    @Transactional
    public void releaseAllForEvent(UUID eventId) {
        logRepository.deleteAll(logRepository.findByEventId(eventId));
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
        if (!eventIds.isEmpty()) {
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