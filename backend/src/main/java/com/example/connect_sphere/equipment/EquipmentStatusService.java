package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EquipmentStatusService {

    // Stands in for "forever" when checking overlaps for a block with no end date.
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    private final SerialisedEquipmentRepository unitRepository;
    private final EquipmentStatusPeriodRepository periodRepository;

    public EquipmentStatusService(SerialisedEquipmentRepository unitRepository,
                                  EquipmentStatusPeriodRepository periodRepository) {
        this.unitRepository = unitRepository;
        this.periodRepository = periodRepository;
    }

    // Status of every unit for the viewed period: a block if one overlaps, else Available.
    @Transactional(readOnly = true)
    public List<EquipmentUnitResponse> listUnits(Instant start, Instant end) {
        requireValidPeriod(start, end);

        Map<SerialisedEquipmentId, EquipmentStatus> blocked = new HashMap<>();
        for (EquipmentStatusPeriod p : periodRepository.findOverlapping(start, end)) {
            blocked.putIfAbsent(
                    new SerialisedEquipmentId(p.getEquipmentId(), p.getSerialNumber()),
                    p.getStatus());
        }

        return unitRepository.findAll().stream()
                .map(unit -> EquipmentUnitResponse.from(
                        unit, blocked.getOrDefault(unit.getId(), EquipmentStatus.AVAILABLE)))
                .sorted(Comparator.comparing(EquipmentUnitResponse::equipmentName)
                        .thenComparing(EquipmentUnitResponse::serialNumber))
                .toList();
    }

    // All blocks for one unit.
    @Transactional(readOnly = true)
    public List<StatusPeriodResponse> listPeriods(UUID equipmentId, String serialNumber) {
        requireUnit(equipmentId, serialNumber);
        return periodRepository
                .findByEquipmentIdAndSerialNumberOrderByPeriodStartAsc(equipmentId, serialNumber)
                .stream()
                .map(StatusPeriodResponse::from)
                .toList();
    }

    // Add a Faulty/Unavailable block. end == null means no end date.
    @Transactional
    public StatusPeriodResponse addPeriod(UUID equipmentId, String serialNumber,
                                          EquipmentStatus status, Instant start, Instant end) {
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        if (status == EquipmentStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Units are available by default. Remove a block to make a unit available.");
        }
        if (start == null || (end != null && !end.isAfter(start))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "start is required, and end must be after start");
        }
        requireUnit(equipmentId, serialNumber);

        Instant checkEnd = (end != null) ? end : FAR_FUTURE;
        if (!periodRepository.findOverlappingForUnit(
                equipmentId, serialNumber, start, checkEnd).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This unit already has a status for part of that time");
        }

        return StatusPeriodResponse.from(periodRepository.save(
                new EquipmentStatusPeriod(equipmentId, serialNumber, status, start, end)));
    }

    // Removing a block makes the unit Available again for that time.
    @Transactional
    public void removePeriod(UUID periodId) {
        EquipmentStatusPeriod period = periodRepository.findById(periodId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Status period not found"));
        periodRepository.delete(period);
    }

    private void requireUnit(UUID equipmentId, String serialNumber) {
        if (!unitRepository.existsById(new SerialisedEquipmentId(equipmentId, serialNumber))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment unit not found");
        }
    }

    private void requireValidPeriod(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end must be after start");
        }
    }
}