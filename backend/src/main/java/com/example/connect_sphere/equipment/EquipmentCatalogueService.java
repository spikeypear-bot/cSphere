package com.example.connect_sphere.equipment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The equipment catalogue (EO/EC/TECHNICIAN) and, for Technical Support, the
 * catalogue's availability for a window and one kind's units (D32).
 * Availability comes from {@link EquipmentAvailabilityService}, the same
 * calculation TS01 and TS02 use.
 */
@Service
public class EquipmentCatalogueService {

    private final EquipmentRepository equipment;
    private final SerialisedEquipmentRepository units;
    private final EquipmentStatusPeriodRepository statusPeriods;
    private final EquipmentAvailabilityService availability;
    private final Clock clock;

    public EquipmentCatalogueService(EquipmentRepository equipment, SerialisedEquipmentRepository units,
            EquipmentStatusPeriodRepository statusPeriods, EquipmentAvailabilityService availability,
            Clock clock) {
        this.equipment = equipment;
        this.units = units;
        this.statusPeriods = statusPeriods;
        this.availability = availability;
        this.clock = clock;
    }

    /**
     * Every kind by name. With a window (both {@code from} and {@code to},
     * {@code to} after {@code from}) each kind also carries its reserved,
     * out-of-action and available quantities for it; 400 for half a window or
     * one that does not move forward.
     */
    @Transactional(readOnly = true)
    public List<EquipmentCatalogueResponse> catalogue(Instant from, Instant to) {
        if ((from == null) != (to == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give both from and to, or neither.");
        }
        List<Equipment> kinds = equipment.findAll(Sort.by("name"));
        if (from == null) {
            return kinds.stream().map(EquipmentCatalogueResponse::of).toList();
        }
        if (!to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from.");
        }
        Map<UUID, EquipmentAvailabilityService.Availability> byKind = availability.forEquipment(kinds, from, to);
        return kinds.stream()
                .map(kind -> EquipmentCatalogueResponse.of(kind, byKind.get(kind.getId())))
                .toList();
    }

    /** One kind with every unit, its status now and all its blocks. 404 for an unknown kind. */
    @Transactional(readOnly = true)
    public EquipmentDetailResponse detail(UUID equipmentId) {
        Equipment kind = equipment.findById(equipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found"));
        Instant now = Instant.now(clock);
        Map<String, List<EquipmentStatusPeriod>> periodsBySerial = statusPeriods
                .findByEquipmentIdOrderByPeriodStartAsc(equipmentId).stream()
                .collect(Collectors.groupingBy(EquipmentStatusPeriod::getSerialNumber));
        List<EquipmentDetailResponse.Unit> unitList = units.findByIdEquipmentIdOrderByIdSerialNumberAsc(equipmentId)
                .stream()
                .map(unit -> {
                    String serial = unit.getId().getSerialNumber();
                    List<EquipmentStatusPeriod> periods = periodsBySerial.getOrDefault(serial, List.of());
                    EquipmentStatus current = periods.stream()
                            .filter(p -> !p.getPeriodStart().isAfter(now)
                                    && (p.getPeriodEnd() == null || p.getPeriodEnd().isAfter(now)))
                            .map(EquipmentStatusPeriod::getStatus)
                            .findFirst()
                            .orElse(EquipmentStatus.AVAILABLE);
                    return new EquipmentDetailResponse.Unit(serial, current,
                            periods.stream().map(StatusPeriodResponse::from).toList());
                })
                .toList();
        return new EquipmentDetailResponse(kind.getId(), kind.getName(), Boolean.TRUE.equals(kind.getSerialised()),
                kind.getQuantity(), unitList);
    }
}
