package com.example.connect_sphere.equipmentdamage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageReportRepository;
import com.example.connect_sphere.equipmentdamage.service.DamageImpact;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;

/**
 * TS09 AC6, AC11: when a non-serialised kind's reservations exceed its
 * working units. Reservations and damage are stubs; the sweep is real.
 */
@Tag("unit")
@Tag("TS09")
class DamageImpactTest {

    private static final Instant FROM = Instant.parse("2027-03-10T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2027-03-11T00:00:00Z");

    private final EquipmentLogRepository logs = mock(EquipmentLogRepository.class);
    private final EquipmentDamageReportRepository reports = mock(EquipmentDamageReportRepository.class);
    private final UUID equipmentId = UUID.randomUUID();
    private final Equipment kind = mock(Equipment.class);
    private DamageImpact impact;

    @BeforeEach
    void setUp() {
        impact = new DamageImpact(logs, reports, mock(EquipmentRequestRepository.class),
                mock(EquipmentRequestLineRepository.class), Clock.fixed(FROM, ZoneOffset.UTC));
        when(kind.getId()).thenReturn(equipmentId);
        when(kind.getQuantity()).thenReturn(10);
    }

    private EquipmentLog reserved(int quantity, String from, String until) {
        return new EquipmentLog(UUID.randomUUID(), equipmentId, quantity, null, Instant.parse(from), Instant.parse(until));
    }

    private EquipmentDamageReport damaged(int quantity, String from) {
        EquipmentDamageReport report = new EquipmentDamageReport();
        report.setQuantity(quantity);
        report.setPeriodStart(Instant.parse(from));
        return report;
    }

    private void given(List<EquipmentLog> reservations, List<EquipmentDamageReport> damage) {
        when(logs.findOverlappingForEquipment(eq(equipmentId), any(), any())).thenReturn(reservations);
        when(reports.findQuantityDamageOverlapping(eq(equipmentId), any(), any())).thenReturn(damage);
    }

    @Test
    @DisplayName("TS09 AC6 (boundary): back-to-back reservations (one ends as the next starts) are not added together")
    void backToBackReservationsDoNotOverlap() {
        given(List.of(reserved(7, "2027-03-10T01:00:00Z", "2027-03-10T04:00:00Z"),
                        reserved(7, "2027-03-10T04:00:00Z", "2027-03-10T07:00:00Z")),
                List.of(damaged(3, "2027-03-09T00:00:00Z")));

        assertThat(impact.overCommitted(kind, FROM, UNTIL)).isEmpty();
    }

    @Test
    @DisplayName("TS09 AC6, AC11 (conflict): overlapping reservations beyond the working units are one over-committed stretch naming both events and the excess")
    void overlappingReservationsBeyondTheWorkingUnitsAreFound() {
        EquipmentLog first = reserved(6, "2027-03-10T01:00:00Z", "2027-03-10T04:00:00Z");
        EquipmentLog second = reserved(4, "2027-03-10T03:00:00Z", "2027-03-10T06:00:00Z");
        given(List.of(first, second), List.of(damaged(1, "2027-03-09T00:00:00Z")));

        List<DamageImpact.Segment> over = impact.overCommitted(kind, FROM, UNTIL);

        assertThat(over).hasSize(1);
        assertThat(over.get(0).from()).isEqualTo(Instant.parse("2027-03-10T03:00:00Z"));
        assertThat(over.get(0).until()).isEqualTo(Instant.parse("2027-03-10T04:00:00Z"));
        assertThat(over.get(0).excess()).isEqualTo(1);
        assertThat(over.get(0).eventIds()).containsExactly(first.getEventId(), second.getEventId());
    }

    @Test
    @DisplayName("TS09 AC6 (boundary): damage that starts after a reservation's end does not count against it; reserved equal to working units is not over")
    void onlyDamageDuringTheReservationCounts() {
        given(List.of(reserved(8, "2027-03-10T01:00:00Z", "2027-03-10T04:00:00Z")),
                List.of(damaged(3, "2027-03-10T04:00:00Z"), damaged(2, "2027-03-10T00:00:00Z")));

        assertThat(impact.overCommitted(kind, FROM, UNTIL)).isEmpty();
    }
}
