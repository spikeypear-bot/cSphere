package com.example.connect_sphere.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipment.entity.Equipment;
import com.example.connect_sphere.equipment.entity.EquipmentLog;
import com.example.connect_sphere.equipment.entity.EquipmentStatus;
import com.example.connect_sphere.equipment.entity.EquipmentStatusPeriod;
import com.example.connect_sphere.equipment.repository.EquipmentLogRepository;
import com.example.connect_sphere.equipment.repository.EquipmentRepository;
import com.example.connect_sphere.equipment.repository.EquipmentStatusPeriodRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * TS09 (decision-log D32): the one availability calculation behind TS01's
 * request availability, TS02's quantity check and the Technical Support
 * catalogue. Reserved = overlapping reservations (TS02); out of action =
 * blocked serialised units (TS03) plus damaged units of a non-serialised kind
 * (TS09 AC5); available = total − reserved − out of action, never negative.
 * Repositories are stubs: only the arithmetic and the batching are under test.
 */
@Tag("unit")
@Tag("TS09")
@Tag("TS01")
@Tag("TS02")
@Tag("TS03")
class EquipmentAvailabilityServiceTest {

    private static final Instant START = Instant.parse("2027-03-10T01:00:00Z");
    private static final Instant END = Instant.parse("2027-03-10T04:00:00Z");

    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final EquipmentStatusPeriodRepository periods = mock(EquipmentStatusPeriodRepository.class);
    private final EquipmentLogRepository logs = mock(EquipmentLogRepository.class);
    private final EquipmentAvailabilityService calculator =
            new EquipmentAvailabilityService(equipment, periods, logs);

    private static Equipment kind(int quantity, boolean serialised) {
        Equipment kind = mock(Equipment.class);
        when(kind.getId()).thenReturn(UUID.randomUUID());
        when(kind.getQuantity()).thenReturn(quantity);
        when(kind.getSerialised()).thenReturn(serialised);
        return kind;
    }

    private static EquipmentLog reservation(Equipment kind, int quantity) {
        return new EquipmentLog(UUID.randomUUID(), kind.getId(), quantity, null, START, END);
    }

    private static EquipmentStatusPeriod block(Equipment kind, String serial) {
        return new EquipmentStatusPeriod(kind.getId(), serial, EquipmentStatus.FAULTY, START, null, Instant.now());
    }

    @Test
    @DisplayName("TS02 TS03 TS09 (negative): open damage excludes an unreserved unit from inventory and automatic allocation")
    void openDamageExcludesUnreservedUnit() {
        Equipment mixers = kind(3, true);
        UUID id = mixers.getId();
        when(equipment.openlyDamagedUnits(anyCollection(), eq(START), eq(END)))
                .thenReturn(List.<Object[]>of(new Object[] {id, "MX-1"}));
        when(equipment.openlyDamagedSerials(mixers.getId(), START, END)).thenReturn(List.of("MX-1"));

        assertThat(calculator.forEquipment(mixers, START, END))
                .isEqualTo(new EquipmentAvailabilityService.Availability(3, 0, 1, 2));
        assertThat(calculator.unitsNotFree(mixers.getId(), START, END)).containsExactly("MX-1");
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("TS02 TS03 TS09 (boundary): a faulty reserved unit and duplicate damage reports consume stock once")
    void faultyReservedUnitConsumesStockOnce() {
        Equipment mixers = kind(3, true);
        UUID id = mixers.getId();
        List<EquipmentStatusPeriod> blocked = List.of(block(mixers, "MX-1"));
        when(logs.findOverlappingForEquipments(anyCollection(), eq(START), eq(END)))
                .thenReturn(List.of(new EquipmentLog(UUID.randomUUID(), id, 1, "MX-1", START, END)));
        when(periods.findOverlapping(START, END)).thenReturn(blocked);
        when(equipment.openlyDamagedUnits(anyCollection(), eq(START), eq(END)))
                .thenReturn(List.<Object[]>of(new Object[] {id, "MX-1"}, new Object[] {id, "MX-1"}));

        assertThat(calculator.forEquipment(mixers, START, END))
                .isEqualTo(new EquipmentAvailabilityService.Availability(3, 1, 1, 2));
    }

    @Test
    @DisplayName("TS09 (happy): 10 owned, 3 + 2 reserved and 1 damaged leaves 4 available")
    void nonSerialisedKindSubtractsReservationsAndDamage() {
        Equipment mics = kind(10, false);
        List<EquipmentLog> reserved = List.of(reservation(mics, 3), reservation(mics, 2));
        List<Object[]> damaged = List.<Object[]>of(new Object[] {mics.getId(), 1L});
        when(logs.findOverlappingForEquipments(anyCollection(), eq(START), eq(END))).thenReturn(reserved);
        when(equipment.damagedQuantities(anyCollection(), eq(START), eq(END))).thenReturn(damaged);

        assertThat(calculator.forEquipment(mics, START, END)).isEqualTo(new EquipmentAvailabilityService.Availability(10, 5, 1, 4));
    }

    @Test
    @DisplayName("TS09 (boundary): a unit with two blocks counts once; another kind's blocks and a serialised kind's damage rows do not count")
    void serialisedKindCountsEachBlockedUnitOnce() {
        Equipment projectors = kind(4, true);
        Equipment other = kind(4, true);
        List<EquipmentStatusPeriod> blocks = List.of(
                block(projectors, "PJ-1"), block(projectors, "PJ-1"), block(projectors, "PJ-2"), block(other, "PJ-3"));
        List<Object[]> damaged = List.<Object[]>of(new Object[] {projectors.getId(), 3L});
        when(periods.findOverlapping(START, END)).thenReturn(blocks);
        when(equipment.damagedQuantities(anyCollection(), eq(START), eq(END))).thenReturn(damaged);

        assertThat(calculator.forEquipment(projectors, START, END)).isEqualTo(new EquipmentAvailabilityService.Availability(4, 0, 2, 2));
    }

    @Test
    @DisplayName("TS09 (boundary): reservations and damage beyond the stock give 0 available, never a negative figure; exactly the stock also gives 0")
    void availabilityNeverGoesBelowZero() {
        Equipment over = kind(3, false);
        Equipment exact = kind(3, false);
        List<EquipmentLog> reserved = List.of(reservation(over, 3), reservation(exact, 2));
        List<Object[]> damaged = List.<Object[]>of(new Object[] {over.getId(), 2L}, new Object[] {exact.getId(), 1L});
        when(logs.findOverlappingForEquipments(anyCollection(), eq(START), eq(END))).thenReturn(reserved);
        when(equipment.damagedQuantities(anyCollection(), eq(START), eq(END))).thenReturn(damaged);

        Map<UUID, EquipmentAvailabilityService.Availability> result = calculator.forEquipment(List.of(over, exact), START, END);

        assertThat(result.get(over.getId())).isEqualTo(new EquipmentAvailabilityService.Availability(3, 3, 2, 0));
        assertThat(result.get(exact.getId())).isEqualTo(new EquipmentAvailabilityService.Availability(3, 2, 1, 0));
    }

    @Test
    @DisplayName("TS09 (cross-cutting): many kinds use one query each for reservations, blocks, serialised damage and quantity damage, in the order given")
    void manyKindsUseOneQueryEach() {
        Equipment first = kind(5, false);
        Equipment second = kind(2, true);
        Equipment third = kind(7, false);

        Map<UUID, EquipmentAvailabilityService.Availability> result = calculator.forEquipment(List.of(first, second, third), START, END);

        assertThat(result.keySet()).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(result.get(third.getId())).isEqualTo(new EquipmentAvailabilityService.Availability(7, 0, 0, 7));
        verify(logs, times(1)).findOverlappingForEquipments(anyCollection(), any(), any());
        verify(periods, times(1)).findOverlapping(START, END);
        verify(equipment, times(1)).damagedQuantities(anyCollection(), any(), any());
        verify(equipment, times(1)).openlyDamagedUnits(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("TS09 (boundary): no kinds asks the database nothing")
    void noKindsRunsNoQuery() {
        assertThat(calculator.forEquipment(List.of(), START, END)).isEmpty();

        verify(logs, never()).findOverlappingForEquipments(anyCollection(), any(), any());
        verify(periods, never()).findOverlapping(any(), any());
        verify(equipment, never()).damagedQuantities(anyCollection(), any(), any());
        verify(equipment, never()).openlyDamagedUnits(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("TS02 (boundary): a unit held only by a reservation being given up (D40) is free; a reservation with no serial number names no unit")
    void ignoredReservationsLeaveTheirUnitsFree() {
        Equipment mixers = kind(3, true);
        UUID id = mixers.getId();
        EquipmentLog givenUp = new EquipmentLog(UUID.randomUUID(), id, 1, "MX-1", START, END);
        EquipmentLog kept = new EquipmentLog(UUID.randomUUID(), id, 1, "MX-2", START, END);
        when(logs.findOverlappingForUnit(id, "MX-1", START, END)).thenReturn(List.of(givenUp));
        EquipmentLog unnamed = new EquipmentLog(UUID.randomUUID(), id, 1, null, START, END);
        when(logs.findOverlappingForEquipment(id, START, END)).thenReturn(List.of(givenUp, kept, unnamed));

        assertThat(calculator.unitConflict(id, "MX-1", START, END, java.util.Set.of(givenUp.getId()))).isEmpty();
        assertThat(calculator.unitConflict(id, "MX-1", START, END))
                .contains(EquipmentAvailabilityService.UnitConflict.RESERVED);
        assertThat(calculator.unitsNotFree(id, START, END, java.util.Set.of(givenUp.getId())))
                .containsExactly("MX-2");
    }
}
