package com.example.connect_sphere.equipmentdamage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.EventChange;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipmentdamage.dto.EventEquipmentDamageDto;
import com.example.connect_sphere.equipmentdamage.dto.ResolveShortfallRequest;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;
import com.example.connect_sphere.equipmentdamage.entity.ShortfallResolution;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageReportRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;

/**
 * TS09 AC17–AC20, coordinator side: the damage affecting one event and what
 * was decided for it. The assigned coordinator acts; the Lead reads any event
 * (ECL-C7); any other coordinator is refused.
 */
@Service
public class EventEquipmentDamageService {

    private final EquipmentDamageOutcomeRepository outcomes;
    private final EquipmentDamageReportRepository reports;
    private final EquipmentRepository equipment;
    private final EquipmentRequestRepository requests;
    private final EventRepository events;
    private final EquipmentShortfallLedger ledger;
    private final ApplicationEventPublisher changeLog;
    private final Clock clock;

    public EventEquipmentDamageService(EquipmentDamageOutcomeRepository outcomes,
            EquipmentDamageReportRepository reports, EquipmentRepository equipment,
            EquipmentRequestRepository requests, EventRepository events, EquipmentShortfallLedger ledger,
            ApplicationEventPublisher changeLog, Clock clock) {
        this.outcomes = outcomes;
        this.reports = reports;
        this.equipment = equipment;
        this.requests = requests;
        this.events = events;
        this.ledger = ledger;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    /** AC17. {@code lead} reads any event without being able to act on it. */
    @Transactional(readOnly = true)
    public List<EventEquipmentDamageDto> forEvent(UUID viewerId, boolean lead, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!lead) {
            requireAssigned(viewerId, event);
        }
        boolean processing = requests.findByEventId(eventId).stream()
                .anyMatch(r -> r.getStatus() == EquipmentRequestStatus.processing);
        return outcomes.findByEventId(eventId).stream()
                .map(outcome -> toDto(outcome, event, lead, processing))
                .sorted(Comparator.comparing(EventEquipmentDamageDto::reportedAt).reversed())
                .toList();
    }

    /** AC20: the assigned coordinator accepts a shortfall with a note. */
    @Transactional
    public EventEquipmentDamageDto resolve(UUID coordinatorId, UUID eventId, UUID outcomeId,
            ResolveShortfallRequest input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        EquipmentDamageOutcome outcome = ledger.lockForEvent(eventId, outcomeId);
        if (!outcome.isOpenShortfall()) {
            throw EquipmentDamageService.conflict("This equipment shortfall is not open.");
        }
        if (ledger.linkedStatus(outcome).filter(s -> s == EquipmentRequestStatus.processing).isPresent()) {
            throw EquipmentDamageService.conflict("An additional request for this shortfall is awaiting Technical "
                    + "Support. Wait for their decision before resolving it.");
        }
        String note = EquipmentDamageService.requireText(input == null ? null : input.note(),
                "Say why the shortfall is acceptable.");
        outcome.setResolution(ShortfallResolution.NOTE);
        outcome.setResolutionNote(note);
        outcome.setResolvedBy(coordinatorId);
        outcome.setResolvedAt(Instant.now(clock));
        outcomes.save(outcome);
        changeLog.publishEvent(new EventChange(eventId, ActivityType.equipment_shortfall_resolved, coordinatorId,
                "Accepted being short of " + outcome.getShortfall() + " " + kindOf(outcome).getName() + ": " + note));
        return toDto(outcome, event, false, false);
    }

    private EventEquipmentDamageDto toDto(EquipmentDamageOutcome outcome, Event event, boolean lead,
            boolean processing) {
        EquipmentDamageReport report = reports.findById(outcome.getReportId()).orElseThrow();
        Equipment kind = kindOf(outcome);
        Optional<EquipmentRequestStatus> linked = ledger.linkedStatus(outcome);
        boolean planning = event.getStatus().isPlanningEditable();
        boolean canRequest = !lead && planning && !processing && ledger.canRequestAdditional(outcome);
        boolean canResolve = !lead && outcome.isOpenShortfall()
                && linked.filter(s -> s == EquipmentRequestStatus.processing).isEmpty();
        return new EventEquipmentDamageDto(outcome.getOutcomeId(), report.getReportId(), kind.getId(), kind.getName(),
                report.getSerialNumber(), report.getQuantity(), report.getDescription(), report.getPeriodStart(),
                report.getPeriodEnd(), report.getReportedAt(),
                outcome.getOutcome() == null ? null : outcome.getOutcome().name(), outcome.getShortfall(),
                outcome.getReplacementSerialNumber(), outcome.getDecidedAt(),
                outcome.getResolution() == null ? null : outcome.getResolution().name(), outcome.getResolutionNote(),
                outcome.getResolvedAt(), outcome.getAdditionalRequestId(), linked.map(Enum::name).orElse(null),
                canRequest, canResolve);
    }

    private Equipment kindOf(EquipmentDamageOutcome outcome) {
        UUID equipmentId = reports.equipmentIdOf(outcome.getReportId()).orElseThrow();
        return equipment.findById(equipmentId).orElseThrow();
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }
}
