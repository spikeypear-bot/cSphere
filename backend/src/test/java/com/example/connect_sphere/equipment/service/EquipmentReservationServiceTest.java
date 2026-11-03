package com.example.connect_sphere.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.connect_sphere.equipment.entity.EquipmentLog;
import com.example.connect_sphere.equipment.repository.EquipmentLogRepository;
import com.example.connect_sphere.equipment.repository.EquipmentRepository;
import com.example.connect_sphere.equipment.repository.SerialisedEquipmentRepository;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestRepository;
import com.example.connect_sphere.event.repository.EventRepository;

/** EC-NEW3 (D40) rules that only shape lists of reservations, without a database. */
@Tag("unit")
@Tag("EC-NEW3")
@Tag("EC07")
class EquipmentReservationServiceTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID REVISION = UUID.randomUUID();
    private static final UUID SCREEN = UUID.randomUUID();
    private static final UUID MIC = UUID.randomUUID();
    private static final UUID CABLE = UUID.randomUUID();
    // The event's new time, and its old one earlier the same day.
    private static final Instant START = Instant.parse("2027-03-10T06:00:00Z");
    private static final Instant END = Instant.parse("2027-03-10T09:00:00Z");
    private static final Instant OLD_START = Instant.parse("2027-03-10T01:00:00Z");
    private static final Instant OLD_END = Instant.parse("2027-03-10T04:00:00Z");

    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final EquipmentLogRepository logs = mock(EquipmentLogRepository.class);
    private final EquipmentAvailabilityService availability = mock(EquipmentAvailabilityService.class);
    private final EquipmentReservationService service = new EquipmentReservationService(equipment,
            mock(SerialisedEquipmentRepository.class), logs, mock(EquipmentRequestLineRepository.class),
            mock(EquipmentRequestRepository.class), availability, mock(EventRepository.class));

    private static EquipmentLog unit(String serial, boolean spare) {
        return new EquipmentLog(EVENT, SCREEN, 1, serial, START, END, spare);
    }

    @Test
    @DisplayName("EC-NEW3 (happy): approving a revision keeps what still fits the new time, up to each kind's new quantity, and releases the rest, the last serial numbers first")
    void keepOnlyKeepsWhatTheRevisionNeeds() {
        EquipmentLog s1 = unit("SCR-01", false), s2 = unit("SCR-02", false), s3 = unit("SCR-03", false);
        EquipmentLog spare = unit("SCR-04", true);
        EquipmentLog mics = new EquipmentLog(EVENT, MIC, 5, null, START, END, false);
        EquipmentLog oldMics = new EquipmentLog(EVENT, MIC, 2, null, OLD_START, OLD_END, false);
        EquipmentLog cables = new EquipmentLog(EVENT, CABLE, 4, null, START, END, false);
        // Mics that start an hour late, or end an hour early, no longer cover the event.
        EquipmentLog lateMics = new EquipmentLog(EVENT, MIC, 1, null, START.plusSeconds(3600), END, false);
        EquipmentLog earlyMics = new EquipmentLog(EVENT, MIC, 1, null, START, END.minusSeconds(3600), false);
        when(logs.findByEventId(EVENT)).thenReturn(List.of(s3, mics, spare, cables, s1, oldMics, s2, lateMics, earlyMics));

        service.keepOnly(EVENT, REVISION, Map.of(SCREEN, 2, MIC, 3), START, END);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<EquipmentLog>> released = ArgumentCaptor.forClass(Iterable.class);
        verify(logs).deleteAll(released.capture());
        // SCR-03 is over the new quantity of 2; the old-time mics and the no-longer-wanted cables go too.
        assertThat(released.getValue()).containsExactlyInAnyOrder(s3, oldMics, cables, lateMics, earlyMics);
        assertThat(mics.getQuantity()).isEqualTo(3);
        assertThat(List.of(s1, s2, spare, mics)).allSatisfy(kept -> assertThat(kept.getRequestId()).isEqualTo(REVISION));
    }

    @Test
    @DisplayName("EC-NEW3 (boundary): a change proposing no equipment at all has nothing to compare with free stock, and looks nothing up")
    void noProposedItemsNeedNothing() {
        assertThat(service.needsAt(EVENT, Map.of(), START, END)).isEmpty();
        verifyNoInteractions(equipment, logs, availability);
    }

    @Test
    @DisplayName("EC07 (boundary): with an empty equipment catalogue, the coordinator's form shows no free stock")
    void anEmptyCatalogueHasNoFreeStock() {
        when(equipment.findAll()).thenReturn(List.of());

        assertThat(service.freeForEvent(EVENT, START, END)).isEmpty();
        verifyNoInteractions(logs, availability);
    }
}
