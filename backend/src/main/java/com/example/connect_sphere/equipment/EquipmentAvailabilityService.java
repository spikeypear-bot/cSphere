package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The one place equipment availability is worked out (TS01, D32). TS01's
 * request availability, TS02's quantity check and the Technical Support
 * catalogue all read it, so they cannot disagree.
 *
 * <p>For a period, per kind: reservations of events that are not cancelled
 * or completed (EO07, D28) and that overlap it; serialised units with a
 * Faulty or Unavailable block overlapping it (TS03), each counted once; and
 * units of a non-serialised kind reported damaged during it (TS09 AC5). Three
 * queries cover any number of kinds.
 *
 * <p>It also answers whether one serialised unit is free for a period
 * ({@link #unitConflict}, D35), for TS02's reservations and TS09's
 * replacements alike.
 */
@Component
public class EquipmentAvailabilityService {

    /**
     * How much of one kind can still be reserved for a period.
     * {@code outOfActionQuantity} is the serialised units blocked Faulty or
     * Unavailable (TS03) plus the units of a non-serialised kind reported
     * damaged (TS09 AC5).
     */
    public record Availability(int totalQuantity, int reservedQuantity, int outOfActionQuantity,
            int availableQuantity) {

        /** The availability formula: total − reserved − out of action, never below zero. */
        static Availability of(int total, int reserved, int outOfAction) {
            return new Availability(total, reserved, outOfAction, Math.max(0, total - reserved - outOfAction));
        }
    }

    /** D35: why a serialised unit cannot be used for a period. */
    public enum UnitConflict {
        /** A TS03 Faulty or Unavailable block overlaps it. */
        BLOCKED,
        /** A reservation of an event that is not cancelled or completed overlaps it. */
        RESERVED,
        /** An open TS09 damage report on the unit overlaps it. */
        DAMAGED
    }

    private final EquipmentRepository equipment;
    private final EquipmentStatusPeriodRepository statusPeriods;
    private final EquipmentLogRepository logs;

    public EquipmentAvailabilityService(EquipmentRepository equipment,
            EquipmentStatusPeriodRepository statusPeriods, EquipmentLogRepository logs) {
        this.equipment = equipment;
        this.statusPeriods = statusPeriods;
        this.logs = logs;
    }

    /**
     * TS06, TS09: units of one kind among an event's reservations that are held
     * for the whole of [start, end) (each starting no later and ending no
     * earlier). TS06 approves a request only when these cover it, and TS09
     * reads the same figure, so the two cannot disagree.
     */
    public static int heldThroughout(Collection<EquipmentLog> reservations, UUID equipmentId,
            Instant start, Instant end) {
        return reservations.stream()
                .filter(log -> log.getEquipmentId().equals(equipmentId))
                .filter(log -> !log.getLoanedFrom().isAfter(start) && !log.getLoanedUntil().isBefore(end))
                .mapToInt(EquipmentLog::getQuantity)
                .sum();
    }

    /**
     * D35: the one "is this unit free?" check. Empty when, for [start, end),
     * the unit has no Faulty/Unavailable block (TS03), no reservation on an
     * event that is not cancelled or completed (EO07), and no open damage
     * report (TS09); otherwise the first reason it is not, in that order.
     */
    public Optional<UnitConflict> unitConflict(UUID equipmentId, String serialNumber, Instant start, Instant end) {
        if (!statusPeriods.findOverlappingForUnit(equipmentId, serialNumber, start, end).isEmpty()) {
            return Optional.of(UnitConflict.BLOCKED);
        }
        if (!logs.findOverlappingForUnit(equipmentId, serialNumber, start, end).isEmpty()) {
            return Optional.of(UnitConflict.RESERVED);
        }
        if (equipment.unitOpenlyDamaged(equipmentId, serialNumber, start, end)) {
            return Optional.of(UnitConflict.DAMAGED);
        }
        return Optional.empty();
    }

    /** D35: the serial numbers of one kind's units that {@link #unitConflict}
     * would refuse for [start, end), in three queries for the whole kind. */
    public Set<String> unitsNotFree(UUID equipmentId, Instant start, Instant end) {
        Set<String> taken = new HashSet<>();
        statusPeriods.findOverlappingForEquipment(equipmentId, start, end)
                .forEach(period -> taken.add(period.getSerialNumber()));
        logs.findOverlappingForEquipment(equipmentId, start, end).stream()
                .map(EquipmentLog::getSerialNumber)
                .filter(serial -> serial != null)
                .forEach(taken::add);
        taken.addAll(equipment.openlyDamagedSerials(equipmentId, start, end));
        return taken;
    }

    /** Availability of one kind for [start, end). */
    public Availability forEquipment(Equipment kind, Instant start, Instant end) {
        return forEquipment(List.of(kind), start, end).get(kind.getId());
    }

    /** Availability of each kind for [start, end), keyed by equipment id, in the order given. */
    public Map<UUID, Availability> forEquipment(Collection<Equipment> kinds, Instant start, Instant end) {
        Map<UUID, Availability> result = new LinkedHashMap<>();
        if (kinds.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new HashSet<>();
        kinds.forEach(kind -> ids.add(kind.getId()));

        Map<UUID, Integer> reserved = new HashMap<>();
        for (EquipmentLog log : logs.findOverlappingForEquipments(ids, start, end)) {
            reserved.merge(log.getEquipmentId(), log.getQuantity(), Integer::sum);
        }
        Map<UUID, Set<String>> blockedUnits = new HashMap<>();
        for (EquipmentStatusPeriod period : statusPeriods.findOverlapping(start, end)) {
            if (ids.contains(period.getEquipmentId())) {
                blockedUnits.computeIfAbsent(period.getEquipmentId(), id -> new HashSet<>())
                        .add(period.getSerialNumber());
            }
        }
        Map<UUID, Integer> damaged = new HashMap<>();
        for (Object[] row : equipment.damagedQuantities(ids, start, end)) {
            damaged.put((UUID) row[0], ((Number) row[1]).intValue());
        }

        for (Equipment kind : kinds) {
            UUID id = kind.getId();
            int outOfAction = blockedUnits.getOrDefault(id, Set.of()).size()
                    + (Boolean.TRUE.equals(kind.getSerialised()) ? 0 : damaged.getOrDefault(id, 0));
            result.put(id, Availability.of(kind.getQuantity(), reserved.getOrDefault(id, 0), outOfAction));
        }
        return result;
    }
}
