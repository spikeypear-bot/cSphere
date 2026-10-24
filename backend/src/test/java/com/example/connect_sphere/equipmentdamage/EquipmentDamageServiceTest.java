package com.example.connect_sphere.equipmentdamage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipment.EquipmentStatusPeriodRepository;
import com.example.connect_sphere.equipment.EquipmentStatusService;
import com.example.connect_sphere.equipment.SerialisedEquipmentRepository;
import com.example.connect_sphere.equipmentdamage.dto.DecideOutcomeRequest;
import com.example.connect_sphere.equipmentdamage.entity.DamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.DamageReportStatus;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageReportRepository;
import com.example.connect_sphere.equipmentdamage.service.DamageImpact;
import com.example.connect_sphere.equipmentdamage.service.EquipmentDamageService;
import com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS09 AC16, AC18, AC22: what follows a decision. Outside a transaction the
 * "after commit" notification runs at once, so a mock shows whether it was
 * sent, to whom and with what. Collaborators are stubs (canned answers);
 * notifications and the lifecycle are mocks, because the requirement is the
 * interaction itself.
 */
@Tag("unit")
@Tag("TS09")
class EquipmentDamageServiceTest {

    private final EquipmentDamageReportRepository reports = mock(EquipmentDamageReportRepository.class);
    private final EquipmentDamageOutcomeRepository outcomes = mock(EquipmentDamageOutcomeRepository.class);
    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final SerialisedEquipmentRepository units = mock(SerialisedEquipmentRepository.class);
    private final EquipmentStatusPeriodRepository periods = mock(EquipmentStatusPeriodRepository.class);
    private final EquipmentLogRepository logs = mock(EquipmentLogRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventLifecycleService lifecycle = mock(EventLifecycleService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final JourneyStatusResolver statuses = mock(JourneyStatusResolver.class);
    private final DamageImpact impact = mock(DamageImpact.class);
    private final EquipmentShortfallLedger ledger = mock(EquipmentShortfallLedger.class);
    private final ApplicationEventPublisher changeLog = mock(ApplicationEventPublisher.class);

    private final UUID technician = UUID.randomUUID();
    private final UUID coordinator = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();
    private EquipmentDamageService service;
    private EquipmentDamageReport report;
    private EquipmentDamageOutcome outcome;
    private Event event;
    private EquipmentLog damagedLog;

    @BeforeEach
    void setUp() {
        service = new EquipmentDamageService(reports, outcomes, equipment, units, periods,
                mock(EquipmentStatusService.class), logs, mock(EquipmentRequestRepository.class), events, lifecycle,
                notifications, mock(UserRepository.class), statuses, impact, ledger, changeLog,
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

        Equipment kind = mock(Equipment.class);
        when(kind.getId()).thenReturn(equipmentId);
        when(kind.getName()).thenReturn("Projector");
        when(kind.getSerialised()).thenReturn(true);
        when(equipment.findForUpdate(equipmentId)).thenReturn(Optional.of(kind));

        event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Gala");
        event.setStatus(EventStatus.confirmed);
        event.setCoordinatorId(coordinator);
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T01:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T04:00:00Z"));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));

        report = new EquipmentDamageReport();
        report.setReportId(UUID.randomUUID());
        report.setEquipmentId(equipmentId);
        report.setSerialNumber("PJ-1");
        report.setQuantity(1);
        report.setPeriodStart(Instant.parse("2027-03-01T00:00:00Z"));
        report.setStatus(DamageReportStatus.OPEN);
        report.setReportedBy(technician);
        when(reports.equipmentIdOf(report.getReportId())).thenReturn(Optional.of(equipmentId));
        when(reports.findForUpdate(report.getReportId())).thenReturn(Optional.of(report));

        outcome = new EquipmentDamageOutcome(report.getReportId(), event.getEventId());
        when(outcomes.findForUpdate(outcome.getOutcomeId())).thenReturn(Optional.of(outcome));
        when(outcomes.findByReportId(report.getReportId())).thenReturn(List.of(outcome));

        damagedLog = new EquipmentLog(event.getEventId(), equipmentId, 1, "PJ-1",
                Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T04:00:00Z"));
        when(impact.isActive(event)).thenReturn(true);
        when(impact.touched(report, event.getEventId())).thenReturn(List.of(damagedLog));
        when(units.existsById(any())).thenReturn(true);
        when(statuses.journeyOf(event)).thenReturn(JourneyStatus.CONFIRMED);
    }

    private void decide(String decision, String replacement) {
        service.decide(technician, report.getReportId(), outcome.getOutcomeId(),
                new DecideOutcomeRequest(decision, replacement, null));
    }

    @Test
    @DisplayName("TS09 AC16, AC18 (happy): a replacement tells the assigned coordinator once, naming the new unit, and leaves the event's status alone")
    void aReplacementIsNotifiedWithoutAffectingStatus() {
        decide("REPLACE", "PJ-2");

        assertThat(outcome.getOutcome()).isEqualTo(DamageOutcome.REPLACED);
        assertThat(damagedLog.getSerialNumber()).isEqualTo("PJ-2");
        verify(notifications, times(1)).createEquipmentDamageOutcomeNotification(eq(coordinator),
                eq(event.getEventId()), eq("Gala"),
                eq("Projector PJ-1 was reported damaged. It has been replaced with PJ-2."));
        verify(lifecycle, never()).returnToPlanning(any(), any(), anyString());
    }

    @Test
    @DisplayName("TS09 AC12, AC16, AC18 (happy): a shortfall tells the coordinator by how much and what to do, and sends the confirmed event back to Planning")
    void aShortfallIsNotifiedAndReturnsTheEventToPlanning() {
        when(ledger.need(event.getEventId(), equipmentId, outcome.getOutcomeId())).thenReturn(2);
        when(impact.reservedForEvent(event, equipmentId, Set.of(damagedLog.getId()))).thenReturn(1);

        decide("SHORT", null);

        assertThat(outcome.getShortfall()).isEqualTo(1);
        verify(logs).deleteAll(List.of(damagedLog));
        verify(notifications).createEquipmentDamageOutcomeNotification(eq(coordinator), eq(event.getEventId()),
                eq("Gala"), argThat(message -> message.contains("short by 1")
                        && message.contains("Submit an additional equipment request")));
        verify(lifecycle).returnToPlanning(eq(event.getEventId()), eq(technician), anyString());
    }

    @Test
    @DisplayName("TS09 AC13, AC22 (failure): a refused replacement changes nothing and tells no one")
    void aRefusedDecisionSendsNothing() {
        when(logs.findOverlappingForUnit(eq(equipmentId), eq("PJ-2"), any(), any())).thenReturn(List.of(
                new EquipmentLog(UUID.randomUUID(), equipmentId, 1, "PJ-2", damagedLog.getLoanedFrom(),
                        damagedLog.getLoanedUntil())));

        assertThatThrownBy(() -> decide("REPLACE", "PJ-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already reserved for an overlapping event");

        assertThat(outcome.getOutcome()).isNull();
        assertThat(damagedLog.getSerialNumber()).isEqualTo("PJ-1");
        verify(notifications, never()).createEquipmentDamageOutcomeNotification(any(), any(), any(), any());
        verify(changeLog, never()).publishEvent(any(Object.class));
        verify(lifecycle, never()).returnToPlanning(any(), any(), anyString());
    }

    @Test
    @DisplayName("TS09 AC16 (negative): an event with no assigned coordinator records the outcome without a notification")
    void noCoordinatorMeansNoNotification() {
        event.setCoordinatorId(null);

        decide("REPLACE", "PJ-2");

        assertThat(outcome.getOutcome()).isEqualTo(DamageOutcome.REPLACED);
        verify(notifications, never()).createEquipmentDamageOutcomeNotification(any(), any(), any(), any());
    }
}
