package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        return new EquipmentStatusPeriod(kind.getId(), serial, EquipmentStatus.FAULTY, START, null);
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
    @DisplayName("TS09 (cross-cutting): many kinds are worked out with one query each for reservations, blocks and damage, in the order given")
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
    }

    @Test
    @DisplayName("TS09 (boundary): no kinds asks the database nothing")
    void noKindsRunsNoQuery() {
        assertThat(calculator.forEquipment(List.of(), START, END)).isEmpty();

        verify(logs, never()).findOverlappingForEquipments(anyCollection(), any(), any());
        verify(periods, never()).findOverlapping(any(), any());
        verify(equipment, never()).damagedQuantities(anyCollection(), any(), any());
    }
}
