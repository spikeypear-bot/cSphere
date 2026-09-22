package com.example.connect_sphere.equipment;

import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    public EquipmentReservationService(
            EquipmentRepository equipmentRepository,
            SerialisedEquipmentRepository unitRepository,
            EquipmentStatusPeriodRepository statusPeriodRepository,
            EquipmentLogRepository logRepository,
            EquipmentRequestLineRepository requestLineRepository) {
        this.equipmentRepository = equipmentRepository;
        this.unitRepository = unitRepository;
        this.statusPeriodRepository = statusPeriodRepository;
        this.logRepository = logRepository;
        this.requestLineRepository = requestLineRepository;
    }

    // AC 3 + 4: what's available, and how much, for a period.
    // Only equipment types listed in the event's equipment_request_equipments are returned (AC 2 scope).
    @Transactional(readOnly = true)
    public List<EquipmentAvailabilityResponse> availabilityForEvent(
            UUID requestId, Instant start, Instant end) {
        requireValidPeriod(start, end);

        return requestLineRepository.findByIdRequestId(requestId).stream()
                .map(line -> {
                    Equipment equipment = line.getEquipment();
                    int available = availableQuantity(equipment.getId(), start, end);
                    return new EquipmentAvailabilityResponse(
                            equipment.getId(), equipment.getName(),
                            Boolean.TRUE.equals(equipment.getSerialised()),
                            equipment.getQuantity(), available);
                })
                .toList();
    }

    // The formula: total − reserved elsewhere (overlapping) − blocked units (Faulty/Unavailable).
    private int availableQuantity(UUID equipmentId, Instant start, Instant end) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found"));

        int reservedElsewhere = logRepository.findOverlappingForEquipment(equipmentId, start, end)
                .stream().mapToInt(EquipmentLog::getQuantity).sum();

        long blockedUnits = statusPeriodRepository.findOverlapping(start, end).stream()
                .filter(p -> p.getEquipmentId().equals(equipmentId))
                .map(EquipmentStatusPeriod::getSerialNumber)
                .distinct()
                .count();

        return Math.max(0, equipment.getQuantity() - reservedElsewhere - (int) blockedUnits);
    }

    // AC 5-9: reserve equipment for an event.
    @Transactional
    public EquipmentReservationResponse reserve(ReserveEquipmentRequest req) {
        requireValidPeriod(req.start(), req.end());
        if (req.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be at least 1");
        }

        Equipment equipment = equipmentRepository.findById(req.equipmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found"));

        if (req.serialNumber() != null) {
            reserveSpecificUnit(equipment, req);
        } else {
            reserveByQuantity(equipment, req);
        }

        EquipmentLog log = new EquipmentLog(
                req.eventId(), req.equipmentId(), req.quantity(),
                req.serialNumber(), req.start(), req.end());
        return EquipmentReservationResponse.from(logRepository.save(log), equipment.getName());
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
        int available = availableQuantity(equipment.getId(), req.start(), req.end());
        if (req.quantity() > available) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only " + available + " of " + equipment.getName() + " available for that period");
        }
    }

    // AC 11: reservations already made for an event.
    @Transactional(readOnly = true)
    public List<EquipmentReservationResponse> reservationsForEvent(UUID eventId) {
        return logRepository.findByEventId(eventId).stream()
                .map(log -> {
                    String equipment = equipmentRepository.findById(log.getEquipmentId())
                            .map(Equipment::getName).orElse("Unknown");
                    return EquipmentReservationResponse.from(log, equipment);
                })
                .toList();
    }

    private void requireValidPeriod(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end must be after start");
        }
    }
}