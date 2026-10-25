package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/**
 * TS09 (decision-log D32): the Technical Support console's equipment reads —
 * the catalogue with and without a window, and one kind's detail. The
 * window's validation and every unit's status periods are covered end to end
 * by {@code TechnicalSupportConsoleFlowTest}. The clock is fixed at
 * {@link #NOW}; repositories are stubs, and the availability calculator is a
 * mock only to show the plain catalogue never asks it anything.
 */
@Tag("unit")
@Tag("TS09")
@Tag("TS01")
@Tag("TS03")
class EquipmentConsoleReadsTest {

    private static final Instant NOW = Instant.parse("2027-03-10T02:00:00Z");
    private static final Instant FROM = Instant.parse("2027-03-10T01:00:00Z");
    private static final Instant TO = Instant.parse("2027-03-10T04:00:00Z");

    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final SerialisedEquipmentRepository units = mock(SerialisedEquipmentRepository.class);
    private final EquipmentStatusPeriodRepository periods = mock(EquipmentStatusPeriodRepository.class);
    private final EquipmentAvailabilityService availability = mock(EquipmentAvailabilityService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EquipmentCatalogueService catalogue =
            new EquipmentCatalogueService(equipment, units, periods, availability, clock);

    private final UUID projectorsId = UUID.randomUUID();
    private Equipment projectors;

    @BeforeEach
    void setUp() {
        projectors = mock(Equipment.class);
        when(projectors.getId()).thenReturn(projectorsId);
        when(projectors.getName()).thenReturn("Projector");
        when(projectors.getQuantity()).thenReturn(3);
        when(projectors.getSerialised()).thenReturn(true);
        when(equipment.findAll(any(Sort.class))).thenReturn(List.of(projectors));
        when(equipment.findById(projectorsId)).thenReturn(Optional.of(projectors));
        when(equipment.findAllById(anyCollection())).thenReturn(List.of(projectors));
    }

    private static SerialisedEquipment unit(UUID equipmentId, String serial) {
        SerialisedEquipment unit = mock(SerialisedEquipment.class);
        when(unit.getId()).thenReturn(new SerialisedEquipmentId(equipmentId, serial));
        return unit;
    }

    // ---- Catalogue ------------------------------------------------------------

    @Test
    @DisplayName("TS09 (cross-cutting): without a window the catalogue has no quantity fields beyond total and never computes availability")
    void plainCatalogueSkipsAvailability() {
        List<EquipmentCatalogueResponse> listed = catalogue.catalogue(null, null);

        assertThat(listed).containsExactly(new EquipmentCatalogueResponse(projectorsId, "Projector", 3, true,
                null, null, null));
        verifyNoInteractions(availability);
    }

    @Test
    @DisplayName("TS09 (happy): with a window each kind carries the shared calculation's reserved, out-of-action and available figures")
    void windowedCatalogueUsesTheSharedCalculation() {
        when(availability.forEquipment(List.of(projectors), FROM, TO))
                .thenReturn(Map.of(projectorsId, new EquipmentAvailabilityService.Availability(3, 1, 1, 1)));

        assertThat(catalogue.catalogue(FROM, TO)).containsExactly(
                new EquipmentCatalogueResponse(projectorsId, "Projector", 3, true, 1, 1, 1));
    }

    // ---- Detail ---------------------------------------------------------------

    @Test
    @DisplayName("TS09 (boundary): a unit's status now is the block that has started and not ended; one ending exactly now or starting later leaves it Available")
    void detailStatusIsTheBlockCoveringNow() {
        List<SerialisedEquipment> three = List.of(
                unit(projectorsId, "PJ-1"), unit(projectorsId, "PJ-2"), unit(projectorsId, "PJ-3"));
        when(units.findByIdEquipmentIdOrderByIdSerialNumberAsc(projectorsId)).thenReturn(three);
        EquipmentStatusPeriod startsNow = new EquipmentStatusPeriod(projectorsId, "PJ-1", EquipmentStatus.UNAVAILABLE,
                NOW, null);
        EquipmentStatusPeriod endedNow = new EquipmentStatusPeriod(projectorsId, "PJ-2", EquipmentStatus.FAULTY,
                FROM, NOW);
        EquipmentStatusPeriod later = new EquipmentStatusPeriod(projectorsId, "PJ-2", EquipmentStatus.FAULTY,
                NOW.plusSeconds(1), null);
        when(periods.findByEquipmentIdOrderByPeriodStartAsc(projectorsId))
                .thenReturn(List.of(endedNow, startsNow, later));

        EquipmentDetailResponse detail = catalogue.detail(projectorsId);

        assertThat(detail.equipmentId()).isEqualTo(projectorsId);
        assertThat(detail.serialised()).isTrue();
        assertThat(detail.totalQuantity()).isEqualTo(3);
        assertThat(detail.units()).extracting(EquipmentDetailResponse.Unit::serialNumber)
                .containsExactly("PJ-1", "PJ-2", "PJ-3");
        assertThat(detail.units()).extracting(EquipmentDetailResponse.Unit::status)
                .containsExactly(EquipmentStatus.UNAVAILABLE, EquipmentStatus.AVAILABLE, EquipmentStatus.AVAILABLE);
        assertThat(detail.units().get(1).statusPeriods())
                .containsExactly(StatusPeriodResponse.from(endedNow), StatusPeriodResponse.from(later));
        assertThat(detail.units().get(2).statusPeriods()).isEmpty();
    }
}
