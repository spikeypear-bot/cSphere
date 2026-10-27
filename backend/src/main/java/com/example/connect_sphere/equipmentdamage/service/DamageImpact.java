package com.example.connect_sphere.equipmentdamage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentAvailabilityService;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.equipment.EquipmentStatusService;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageReportRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;

/**
 * TS09: which events a damage report affects, and whether a kind's
 * reservations still fit its stock. Read-only; {@link EquipmentDamageService}
 * acts on the answers.
 */
@Component
public class DamageImpact {

    /** AC6: Planning through Confirmed. */
    private static final Set<EventStatus> ACTIVE = EnumSet.of(EventStatus.pending,
            EventStatus.awaiting_safety_review, EventStatus.safety_changes_requested,
            EventStatus.safety_approved, EventStatus.confirmed);

    private final EquipmentLogRepository logs;
    private final EquipmentDamageReportRepository reports;
    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final Clock clock;

    public DamageImpact(EquipmentLogRepository logs, EquipmentDamageReportRepository reports,
            EquipmentRequestRepository requests, EquipmentRequestLineRepository lines, Clock clock) {
        this.logs = logs;
        this.reports = reports;
        this.requests = requests;
        this.lines = lines;
        this.clock = clock;
    }

    static Instant endOf(EquipmentDamageReport report) {
        return report.getPeriodEnd() != null ? report.getPeriodEnd() : EquipmentStatusService.FAR_FUTURE;
    }

    /** AC6: still running (Planning to Confirmed, not yet over) and relying on
     * an approved request for this kind. Events whose request is still
     * processing are left to TS01/TS06's normal review. */
    public boolean qualifies(Event event, UUID equipmentId) {
        return isActive(event) && requests.findByEventId(event.getEventId()).stream()
                .filter(r -> r.getStatus().heldByTechnicalSupport())
                .flatMap(r -> lines.findByIdRequestId(r.getId()).stream())
                .anyMatch(line -> line.getId().getEquipmentId().equals(equipmentId));
    }

    /** Not cancelled, not completed, and not over. */
    public boolean isActive(Event event) {
        return ACTIVE.contains(event.getStatus())
                && (event.getEndDatetime() == null || !event.getEndDatetime().toInstant().isBefore(Instant.now(clock)));
    }

    /** The reservations the damage touches for one event: the damaged unit's,
     * or (non-serialised) every reservation of the kind during the period. */
    public List<EquipmentLog> touched(EquipmentDamageReport report, UUID eventId) {
        List<EquipmentLog> overlapping = report.isSerialised()
                ? logs.findOverlappingForUnit(report.getEquipmentId(), report.getSerialNumber(),
                        report.getPeriodStart(), endOf(report))
                : logs.findOverlappingForEquipment(report.getEquipmentId(), report.getPeriodStart(), endOf(report));
        return overlapping.stream().filter(log -> log.getEventId().equals(eventId)).toList();
    }

    /** Units of a kind reserved for the event's whole period, as TS06 counts them. */
    public int reservedForEvent(Event event, UUID equipmentId, Set<UUID> leaveOut) {
        List<EquipmentLog> kept = logs.findByEventId(event.getEventId()).stream()
                .filter(log -> !leaveOut.contains(log.getId()))
                .toList();
        return EquipmentAvailabilityService.heldThroughout(kept, equipmentId,
                event.getStartDatetime().toInstant(), event.getEndDatetime().toInstant());
    }

    /** Events (any status) with reservations the damage touches, in reservation order. */
    public Set<UUID> candidateEvents(EquipmentDamageReport report, Equipment equipment) {
        if (report.isSerialised()) {
            Set<UUID> ids = new LinkedHashSet<>();
            logs.findOverlappingForUnit(report.getEquipmentId(), report.getSerialNumber(),
                            report.getPeriodStart(), endOf(report)).stream()
                    .sorted(Comparator.comparing(EquipmentLog::getLoanedFrom))
                    .forEach(log -> ids.add(log.getEventId()));
            return ids;
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Segment segment : overCommitted(equipment, report.getPeriodStart(), endOf(report))) {
            ids.addAll(segment.eventIds());
        }
        return ids;
    }

    /** A stretch of time when a non-serialised kind has more reserved than it has working units. */
    public record Segment(Instant from, Instant until, int reserved, int working, Set<UUID> eventIds) {
        public int excess() {
            return reserved - working;
        }
    }

    /**
     * AC6, AC11: where, inside [start, end), reservations of a non-serialised
     * kind exceed its stock less the damaged units. Splits the period at every
     * reservation and damage boundary and checks each piece, so reservations
     * that never overlap each other are not added together.
     */
    public List<Segment> overCommitted(Equipment equipment, Instant start, Instant end) {
        List<EquipmentLog> reserved = logs.findOverlappingForEquipment(equipment.getId(), start, end);
        if (reserved.isEmpty()) {
            return List.of();
        }
        Instant upper = reserved.stream().map(EquipmentLog::getLoanedUntil).max(Comparator.naturalOrder()).orElse(end);
        if (upper.isAfter(end)) {
            upper = end;
        }
        List<EquipmentDamageReport> damage = reports.findQuantityDamageOverlapping(equipment.getId(), start, upper);

        TreeSet<Instant> cuts = new TreeSet<>(List.of(start, upper));
        for (EquipmentLog log : reserved) {
            cuts.add(log.getLoanedFrom());
            cuts.add(log.getLoanedUntil());
        }
        for (EquipmentDamageReport d : damage) {
            cuts.add(d.getPeriodStart());
            cuts.add(endOf(d));
        }
        List<Instant> points = new ArrayList<>(cuts.subSet(start, true, upper, true));

        List<Segment> result = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            Instant a = points.get(i);
            Instant b = points.get(i + 1);
            List<EquipmentLog> covering = reserved.stream()
                    .filter(log -> log.getLoanedFrom().isBefore(b) && log.getLoanedUntil().isAfter(a)).toList();
            int units = covering.stream().mapToInt(EquipmentLog::getQuantity).sum();
            int damaged = damage.stream()
                    .filter(d -> d.getPeriodStart().isBefore(b) && endOf(d).isAfter(a))
                    .mapToInt(EquipmentDamageReport::getQuantity).sum();
            int working = Math.max(0, equipment.getQuantity() - damaged);
            if (units > working) {
                Set<UUID> events = new LinkedHashSet<>();
                covering.stream().sorted(Comparator.comparing(EquipmentLog::getLoanedFrom))
                        .forEach(log -> events.add(log.getEventId()));
                result.add(new Segment(a, b, units, working, events));
            }
        }
        return result;
    }
}
