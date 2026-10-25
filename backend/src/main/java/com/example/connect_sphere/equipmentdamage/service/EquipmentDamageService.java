package com.example.connect_sphere.equipmentdamage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.EventChange;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipment.EquipmentStatusPeriodRepository;
import com.example.connect_sphere.equipment.EquipmentStatusService;
import com.example.connect_sphere.equipment.SerialisedEquipmentId;
import com.example.connect_sphere.equipment.SerialisedEquipmentRepository;
import com.example.connect_sphere.equipmentdamage.dto.DamageReportDto;
import com.example.connect_sphere.equipmentdamage.dto.DecideOutcomeRequest;
import com.example.connect_sphere.equipmentdamage.dto.ReportDamageRequest;
import com.example.connect_sphere.equipmentdamage.entity.DamageBlockStatus;
import com.example.connect_sphere.equipmentdamage.entity.DamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.DamageReportStatus;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageReportRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS09: Technical Support report damaged equipment (AC1–AC8), decide what
 * happens to each affected event (AC9–AC14), and close the report once every
 * event has an outcome (AC15). The affected event's coordinator is told each
 * outcome (AC16), and an event that is now short after its safety review goes
 * back to Planning (AC18).
 *
 * <p>Every action locks the equipment kind's row first, as TS02 and TS03 do,
 * so two people cannot hand the same unit or the same remaining stock to
 * overlapping reservations (AC14).
 */
@Service
public class EquipmentDamageService {

    static final int MAX_TEXT = 2000;

    private final EquipmentDamageReportRepository reports;
    private final EquipmentDamageOutcomeRepository outcomes;
    private final EquipmentRepository equipment;
    private final SerialisedEquipmentRepository units;
    private final EquipmentStatusPeriodRepository statusPeriods;
    private final EquipmentStatusService statusService;
    private final EquipmentLogRepository logs;
    private final EquipmentRequestRepository requests;
    private final EventRepository events;
    private final EventLifecycleService lifecycle;
    private final NotificationService notifications;
    private final UserRepository users;
    private final JourneyStatusResolver statuses;
    private final DamageImpact impact;
    private final EquipmentShortfallLedger ledger;
    private final ApplicationEventPublisher changeLog;
    private final Clock clock;

    public EquipmentDamageService(EquipmentDamageReportRepository reports, EquipmentDamageOutcomeRepository outcomes,
            EquipmentRepository equipment, SerialisedEquipmentRepository units,
            EquipmentStatusPeriodRepository statusPeriods, EquipmentStatusService statusService,
            EquipmentLogRepository logs, EquipmentRequestRepository requests, EventRepository events,
            EventLifecycleService lifecycle, NotificationService notifications, UserRepository users,
            JourneyStatusResolver statuses, DamageImpact impact, EquipmentShortfallLedger ledger,
            ApplicationEventPublisher changeLog, Clock clock) {
        this.reports = reports;
        this.outcomes = outcomes;
        this.equipment = equipment;
        this.units = units;
        this.statusPeriods = statusPeriods;
        this.statusService = statusService;
        this.logs = logs;
        this.requests = requests;
        this.events = events;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.users = users;
        this.statuses = statuses;
        this.impact = impact;
        this.ledger = ledger;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    // ---- Report (AC1–AC8) ----------------------------------------------------

    @Transactional
    public DamageReportDto report(UUID technicianId, ReportDamageRequest input) {
        if (input == null || input.equipmentId() == null) {
            throw badRequest("Choose the damaged equipment.");
        }
        String description = requireText(input.description(), "Describe the damage.");
        if (input.periodStart() == null) {
            throw badRequest("Give the date and time the damage starts.");
        }
        if (input.periodEnd() != null && !input.periodEnd().isAfter(input.periodStart())) {
            throw badRequest("The end must be after the start.");
        }
        DamageBlockStatus block = input.requestedBlock();
        Equipment kind = equipment.findForUpdate(input.equipmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found."));

        String serial = input.serialNumber() == null ? null : input.serialNumber().strip();
        int quantity;
        if (Boolean.TRUE.equals(kind.getSerialised())) {
            if (serial == null || serial.isEmpty()) {
                throw badRequest("Choose the damaged unit's serial number.");
            }
            if (input.quantity() != null && input.quantity() != 1) {
                throw badRequest("A serialised unit is reported one at a time.");
            }
            if (!units.existsById(new SerialisedEquipmentId(kind.getId(), serial))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment unit not found.");
            }
            quantity = 1;
        } else {
            if (serial != null && !serial.isEmpty()) {
                throw badRequest(kind.getName() + " is not tracked by serial number. Give how many units are damaged.");
            }
            if (block != null) {
                throw badRequest("Only a serialised unit can be marked Faulty or Unavailable.");
            }
            if (input.quantity() == null || input.quantity() < 1) {
                throw badRequest("Give how many units are damaged (at least 1).");
            }
            if (input.quantity() > kind.getQuantity()) {
                throw badRequest("Only " + kind.getQuantity() + " " + kind.getName() + " are owned.");
            }
            serial = null;
            quantity = input.quantity();
        }

        EquipmentDamageReport report = new EquipmentDamageReport();
        report.setReportId(UUID.randomUUID());
        report.setEquipmentId(kind.getId());
        report.setSerialNumber(serial);
        report.setQuantity(quantity);
        report.setDescription(description);
        report.setPeriodStart(input.periodStart());
        report.setPeriodEnd(input.periodEnd());
        report.setBlockStatus(block);
        report.setStatus(DamageReportStatus.OPEN);
        report.setReportedBy(technicianId);
        report.setReportedAt(now());
        reports.saveAndFlush(report);

        for (UUID eventId : impact.candidateEvents(report, kind)) {
            events.findById(eventId)
                    .filter(event -> impact.qualifies(event, kind.getId()))
                    .ifPresent(event -> outcomes.save(new EquipmentDamageOutcome(report.getReportId(), eventId)));
        }
        outcomes.flush();
        if (outcomes.findByReportId(report.getReportId()).isEmpty()) {
            close(report);
        }
        return toDto(report, kind);
    }

    @Transactional(readOnly = true)
    public List<DamageReportDto> list() {
        return list(null);
    }

    /** Every report, newest first; with {@code equipmentId}, only that kind's (D32). */
    @Transactional(readOnly = true)
    public List<DamageReportDto> list(UUID equipmentId) {
        List<EquipmentDamageReport> found = equipmentId == null
                ? reports.findAllByOrderByReportedAtDesc()
                : reports.findByEquipmentIdOrderByReportedAtDesc(equipmentId);
        return found.stream()
                .map(report -> toDto(report, kindOf(report)))
                .toList();
    }

    @Transactional(readOnly = true)
    public DamageReportDto get(UUID reportId) {
        EquipmentDamageReport report = reports.findById(reportId).orElseThrow(DamageReportNotFound::new);
        return toDto(report, kindOf(report));
    }

    // ---- Decide each affected event (AC9–AC14, AC16, AC18) -------------------

    @Transactional
    public DamageReportDto decide(UUID technicianId, UUID reportId, UUID outcomeId, DecideOutcomeRequest input) {
        if (input == null || input.decision() == null || input.decision().isBlank()) {
            throw badRequest("Choose what happens to this event.");
        }
        String decision = input.decision().strip().toUpperCase(Locale.ROOT);
        UUID equipmentId = reports.equipmentIdOf(reportId).orElseThrow(DamageReportNotFound::new);
        Equipment kind = equipment.findForUpdate(equipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found."));
        EquipmentDamageReport report = reports.findForUpdate(reportId).orElseThrow(DamageReportNotFound::new);
        if (report.getStatus() == DamageReportStatus.CLOSED) {
            throw conflict("This report is closed.");
        }
        EquipmentDamageOutcome outcome = outcomes.findForUpdate(outcomeId)
                .filter(o -> o.getReportId().equals(reportId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That event is not affected by this report."));
        Event event = events.findById(outcome.getEventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found."));
        if (!impact.isActive(event)) {
            throw conflict("This event has been cancelled or has finished, so nothing needs deciding for it.");
        }

        DamageOutcome before = outcome.getOutcome();
        String message;
        if (report.isSerialised()) {
            if (outcome.isDecided()) {
                throw conflict("An outcome has already been recorded for this event.");
            }
            message = switch (decision) {
                case "COVERED" -> covered(report, kind, event, outcome);
                case "REPLACE" -> replace(report, kind, event, outcome, input.replacementSerialNumber());
                case "SHORT" -> markShort(report, kind, event, outcome);
                default -> throw badRequest("Decide COVERED, REPLACE or SHORT for a serialised unit.");
            };
        } else {
            if (!"REDUCE".equals(decision)) {
                throw badRequest("For " + kind.getName() + ", decide by reducing this event's reservation (0 or more units).");
            }
            if (outcome.getResolvedAt() != null || outcome.getAdditionalRequestId() != null) {
                throw conflict("The coordinator has already acted on this shortfall, so it can no longer be changed.");
            }
            message = reduce(report, kind, event, outcome, input.reduceBy());
        }
        outcome.setDecidedBy(technicianId);
        outcome.setDecidedAt(now());
        outcomes.save(outcome);

        changeLog.publishEvent(new EventChange(event.getEventId(), ActivityType.equipment_damage_outcome,
                technicianId, message));
        UUID coordinator = event.getCoordinatorId();
        if (coordinator != null) {
            String name = event.getEventName();
            afterCommit(() -> notifications.createEquipmentDamageOutcomeNotification(
                    coordinator, event.getEventId(), name, message));
        }
        if (outcome.getOutcome() == DamageOutcome.SHORT && before != DamageOutcome.SHORT) {
            lifecycle.returnToPlanning(event.getEventId(), technicianId,
                    kind.getName() + " is short for this event after equipment was reported damaged.");
        }
        return toDto(report, kind);
    }

    /** AC9: the event's other reservations still cover its request; the damaged unit's are released. */
    private String covered(EquipmentDamageReport report, Equipment kind, Event event, EquipmentDamageOutcome outcome) {
        List<EquipmentLog> damaged = impact.touched(report, event.getEventId());
        int need = ledger.need(event.getEventId(), kind.getId(), outcome.getOutcomeId());
        int remaining = impact.reservedForEvent(event, kind.getId(), ids(damaged));
        if (remaining < need) {
            throw conflict("Without this unit the event has " + remaining + " of the " + need + " " + kind.getName()
                    + " it asked for, so its spares do not cover it. Replace the unit or record the event as short.");
        }
        logs.deleteAll(damaged);
        outcome.setOutcome(DamageOutcome.COVERED);
        return describe(report, kind) + " The event's spare units still cover the " + need + " it asked for.";
    }

    /** AC10, AC13, AC14: the same reservation, held by a working unit that is free for its whole window. */
    private String replace(EquipmentDamageReport report, Equipment kind, Event event, EquipmentDamageOutcome outcome,
            String replacementSerial) {
        String serial = replacementSerial == null ? "" : replacementSerial.strip();
        if (serial.isEmpty()) {
            throw badRequest("Choose the replacement unit's serial number.");
        }
        if (serial.equals(report.getSerialNumber())) {
            throw badRequest("Choose a different unit from the damaged one.");
        }
        if (!units.existsById(new SerialisedEquipmentId(kind.getId(), serial))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Replacement unit not found among " + kind.getName() + ".");
        }
        List<EquipmentLog> damaged = impact.touched(report, event.getEventId());
        if (damaged.isEmpty()) {
            throw conflict("The damaged unit is no longer reserved for this event.");
        }
        for (EquipmentLog log : damaged) {
            Instant from = log.getLoanedFrom();
            Instant until = log.getLoanedUntil();
            if (!statusPeriods.findOverlappingForUnit(kind.getId(), serial, from, until).isEmpty()) {
                throw conflict(serial + " is marked Faulty or Unavailable for part of this event's reservation.");
            }
            if (!logs.findOverlappingForUnit(kind.getId(), serial, from, until).isEmpty()) {
                throw conflict(serial + " is already reserved for an overlapping event.");
            }
            if (!reports.findUnitDamageOverlapping(kind.getId(), serial, from, until).isEmpty()) {
                throw conflict(serial + " has been reported damaged during this event's reservation.");
            }
        }
        damaged.forEach(log -> log.replaceUnit(serial));
        logs.saveAll(damaged);
        outcome.setOutcome(DamageOutcome.REPLACED);
        outcome.setReplacementSerialNumber(serial);
        return describe(report, kind) + " It has been replaced with " + serial + ".";
    }

    /** AC12: no replacement; the damaged unit's reservations are released and the event is short. */
    private String markShort(EquipmentDamageReport report, Equipment kind, Event event, EquipmentDamageOutcome outcome) {
        List<EquipmentLog> damaged = impact.touched(report, event.getEventId());
        int need = ledger.need(event.getEventId(), kind.getId(), outcome.getOutcomeId());
        int remaining = impact.reservedForEvent(event, kind.getId(), ids(damaged));
        if (remaining >= need) {
            throw conflict("The event's spare units still cover the " + need + " " + kind.getName()
                    + " it asked for. Record it as covered.");
        }
        logs.deleteAll(damaged);
        outcome.setOutcome(DamageOutcome.SHORT);
        outcome.setShortfall(need - remaining);
        return describe(report, kind) + " No replacement is available, so the event is short by "
                + outcome.getShortfall() + ". " + NEXT_STEP;
    }

    /** AC11, AC12: take units off this event's reservations of the kind; the outcome follows from what is left. */
    private String reduce(EquipmentDamageReport report, Equipment kind, Event event, EquipmentDamageOutcome outcome,
            Integer reduceBy) {
        if (reduceBy == null || reduceBy < 0) {
            throw badRequest("Give how many units to take off this event's reservation (0 or more).");
        }
        List<EquipmentLog> touched = impact.touched(report, event.getEventId()).stream()
                .sorted(Comparator.comparingInt(EquipmentLog::getQuantity).reversed())
                .toList();
        int held = touched.stream().mapToInt(EquipmentLog::getQuantity).sum();
        if (reduceBy > held) {
            throw badRequest("This event has only " + held + " " + kind.getName() + " reserved during the damage period.");
        }
        int left = reduceBy;
        for (EquipmentLog log : touched) {
            int take = Math.min(left, log.getQuantity());
            if (take == 0) {
                continue;
            }
            left -= take;
            if (take == log.getQuantity()) {
                logs.delete(log);
            } else {
                log.reduceBy(take);
                logs.save(log);
            }
        }
        logs.flush();
        outcome.setReducedBy(outcome.getReducedBy() + reduceBy);
        int need = ledger.need(event.getEventId(), kind.getId(), outcome.getOutcomeId());
        int reserved = impact.reservedForEvent(event, kind.getId(), Set.of());
        int shortfall = Math.max(0, need - reserved);
        outcome.setShortfall(shortfall);
        outcome.setOutcome(shortfall > 0 ? DamageOutcome.SHORT : DamageOutcome.COVERED);
        String change = reduceBy == 0 ? " This event keeps its reservation."
                : " This event's reservation was reduced by " + reduceBy + ".";
        return describe(report, kind) + change + (shortfall > 0
                ? " It is now short by " + shortfall + ". " + NEXT_STEP
                : " It still has the " + need + " it asked for.");
    }

    private static final String NEXT_STEP =
            "Submit an additional equipment request for the shortfall, or resolve it with a note.";

    // ---- Close (AC15) --------------------------------------------------------

    @Transactional
    public DamageReportDto close(UUID technicianId, UUID reportId) {
        UUID equipmentId = reports.equipmentIdOf(reportId).orElseThrow(DamageReportNotFound::new);
        Equipment kind = equipment.findForUpdate(equipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found."));
        EquipmentDamageReport report = reports.findForUpdate(reportId).orElseThrow(DamageReportNotFound::new);
        if (report.getStatus() == DamageReportStatus.CLOSED) {
            throw conflict("This report is already closed.");
        }
        long undecided = undecided(report);
        if (undecided > 0) {
            throw conflict(undecided + (undecided == 1 ? " affected event still needs" : " affected events still need")
                    + " an outcome.");
        }
        if (!report.isSerialised()) {
            List<DamageImpact.Segment> over = impact.overCommitted(kind, report.getPeriodStart(), DamageImpact.endOf(report));
            if (!over.isEmpty()) {
                int excess = over.stream().mapToInt(DamageImpact.Segment::excess).max().orElse(0);
                throw conflict("Reservations of " + kind.getName() + " still exceed the working units by " + excess
                        + ". Reduce more before closing.");
            }
        }
        close(report);
        return toDto(report, kind);
    }

    private long undecided(EquipmentDamageReport report) {
        return outcomes.findByReportId(report.getReportId()).stream()
                .filter(o -> !o.isDecided())
                .filter(o -> events.findById(o.getEventId()).map(impact::isActive).orElse(false))
                .count();
    }

    /** AC4: the block asked for (TS03 Faulty or Unavailable, V39) is added
     * once no event still holds the unit, since TS03 refuses to block a
     * committed unit. It goes through TS03's own service, so its overlap
     * rules apply unchanged. */
    private void close(EquipmentDamageReport report) {
        DamageBlockStatus block = report.getBlockStatus();
        if (block != null && report.isSerialised()
                && logs.findOverlappingForUnit(report.getEquipmentId(), report.getSerialNumber(),
                        report.getPeriodStart(), DamageImpact.endOf(report)).isEmpty()) {
            statusService.addPeriod(report.getEquipmentId(), report.getSerialNumber(), block.equipmentStatus(),
                    report.getPeriodStart(), report.getPeriodEnd());
            report.setBlockApplied(true);
        }
        report.setStatus(DamageReportStatus.CLOSED);
        report.setClosedAt(now());
        reports.save(report);
    }

    // ---- Read model ----------------------------------------------------------

    private DamageReportDto toDto(EquipmentDamageReport report, Equipment kind) {
        List<DamageReportDto.AffectedEvent> affected = outcomes.findByReportId(report.getReportId()).stream()
                .map(outcome -> affectedEvent(report, kind, outcome))
                .sorted(Comparator.comparing(DamageReportDto.AffectedEvent::eventStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean open = report.getStatus() == DamageReportStatus.OPEN;
        boolean allDecided = affected.stream().allMatch(a -> a.outcome() != null || !a.active());
        boolean fits = report.isSerialised() || !open
                || impact.overCommitted(kind, report.getPeriodStart(), DamageImpact.endOf(report)).isEmpty();
        String reporter = users.findById(report.getReportedBy()).map(User::getUsername).orElse(null);
        DamageBlockStatus block = report.getBlockStatus();
        return new DamageReportDto(report.getReportId(), kind.getId(), kind.getName(), report.isSerialised(),
                report.getSerialNumber(), report.getQuantity(), report.getDescription(), report.getPeriodStart(),
                report.getPeriodEnd(), block == DamageBlockStatus.FAULTY,
                block == DamageBlockStatus.FAULTY && report.isBlockApplied(), report.getStatus().name(),
                report.getReportedBy(), reporter, report.getReportedAt(), report.getClosedAt(),
                open && allDecided && fits, affected, name(block), report.isBlockApplied());
    }

    private DamageReportDto.AffectedEvent affectedEvent(EquipmentDamageReport report, Equipment kind,
            EquipmentDamageOutcome outcome) {
        Event event = events.findById(outcome.getEventId()).orElseThrow();
        List<EquipmentLog> touched = impact.touched(report, event.getEventId());
        int need = ledger.need(event.getEventId(), kind.getId(), outcome.getOutcomeId());
        int reserved = impact.reservedForEvent(event, kind.getId(), Set.of());
        boolean spareCovers = report.isSerialised() && !outcome.isDecided()
                && impact.reservedForEvent(event, kind.getId(), ids(touched)) >= need;
        String requestStatus = outcome.getAdditionalRequestId() == null ? null
                : requests.findById(outcome.getAdditionalRequestId())
                        .map(EquipmentRequest::getStatus).map(Enum::name).orElse(null);
        return new DamageReportDto.AffectedEvent(outcome.getOutcomeId(), event.getEventId(), event.getEventName(),
                event.getStartDatetime(), event.getEndDatetime(), statuses.journeyOf(event).name(), need, reserved,
                touched.stream().mapToInt(EquipmentLog::getQuantity).sum(), spareCovers, impact.isActive(event),
                name(outcome.getOutcome()), outcome.getShortfall(), outcome.getReducedBy(),
                outcome.getReplacementSerialNumber(), outcome.getDecidedAt(), name(outcome.getResolution()),
                outcome.getResolutionNote(), outcome.getResolvedAt(), outcome.getAdditionalRequestId(), requestStatus);
    }

    private Equipment kindOf(EquipmentDamageReport report) {
        return equipment.findById(report.getEquipmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment not found."));
    }

    private static String describe(EquipmentDamageReport report, Equipment kind) {
        return report.isSerialised()
                ? kind.getName() + " " + report.getSerialNumber() + " was reported damaged."
                : report.getQuantity() + " " + kind.getName() + " were reported damaged.";
    }

    private static Set<UUID> ids(List<EquipmentLog> logs) {
        return logs.stream().map(EquipmentLog::getId).collect(Collectors.toSet());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    static String requireText(String raw, String blankMessage) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw badRequest(blankMessage);
        }
        if (text.length() > MAX_TEXT) {
            throw badRequest("This can be at most " + MAX_TEXT + " characters.");
        }
        return text;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /** Notifications go out once the decision has committed (AC22: a failed one sends none). */
    static void afterCommit(Runnable action) {
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

    static final class DamageReportNotFound extends ResponseStatusException {
        DamageReportNotFound() {
            super(HttpStatus.NOT_FOUND, "Damage report not found.");
        }
    }
}
