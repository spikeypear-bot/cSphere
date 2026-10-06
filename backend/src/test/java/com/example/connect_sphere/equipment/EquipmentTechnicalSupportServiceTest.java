package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineId;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class EquipmentTechnicalSupportServiceTest {

    private static final UUID EQUIPMENT_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Instant START = Instant.parse("2027-03-10T09:00:00Z");
    private static final Instant END = Instant.parse("2027-03-10T12:00:00Z");

    @Mock private EquipmentRepository equipmentRepository;
    @Mock private SerialisedEquipmentRepository unitRepository;
    @Mock private EquipmentStatusPeriodRepository periodRepository;
    @Mock private EquipmentLogRepository logRepository;
    @Mock private EquipmentRequestLineRepository requestLineRepository;
    @Mock private EquipmentRequestRepository requestRepository;
    @Mock private Equipment equipment;

    private EquipmentReservationService reservationService;
    private EquipmentStatusService statusService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reservationService = new EquipmentReservationService(
                equipmentRepository, unitRepository, periodRepository, logRepository,
                requestLineRepository, requestRepository);
        statusService = new EquipmentStatusService(
                unitRepository, periodRepository, equipmentRepository, logRepository);

        when(equipment.getId()).thenReturn(EQUIPMENT_ID);
        when(equipment.getName()).thenReturn("Projector");
        when(equipment.getQuantity()).thenReturn(3);
        when(equipment.getSerialised()).thenReturn(false);
        when(equipmentRepository.findForUpdate(EQUIPMENT_ID)).thenReturn(Optional.of(equipment));
        when(equipmentRepository.findById(EQUIPMENT_ID)).thenReturn(Optional.of(equipment));
        when(unitRepository.existsById(any())).thenReturn(true);
        when(periodRepository.findOverlapping(START, END)).thenReturn(List.of());
        when(logRepository.findOverlappingForEquipment(EQUIPMENT_ID, START, END)).thenReturn(List.of());
        when(requestRepository.findByEventId(EVENT_ID)).thenReturn(List.of(
                new EquipmentRequest(REQUEST_ID, EVENT_ID, EquipmentRequestStatus.processing, "Projector")));
        when(requestLineRepository.findByIdRequestId(REQUEST_ID)).thenReturn(List.of(
                new EquipmentRequestLine(
                        new EquipmentRequestLineId(REQUEST_ID, EQUIPMENT_ID), 1)));
    }

    @Test
    void reservationAllowsQuantityExactlyEqualToAvailableAndPersistsIt() {
        when(logRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EquipmentReservationResponse saved = reservationService.reserve(
                new ReserveEquipmentRequest(EVENT_ID, EQUIPMENT_ID, 3, null, START, END));

        assertThat(saved.quantity()).isEqualTo(3);
        assertThat(saved.equipmentName()).isEqualTo("Projector");
        verify(logRepository).save(any(EquipmentLog.class));
    }

    @Test
    void reservationRejectsQuantityAboveAvailable() {
        assertThatThrownBy(() -> reservationService.reserve(
                new ReserveEquipmentRequest(EVENT_ID, EQUIPMENT_ID, 4, null, START, END)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only 3");
    }

    @Test
    void availableStatusChangeSplitsTheExistingBlockedPeriod() {
        Instant earlier = START.minusSeconds(3600);
        Instant later = END.plusSeconds(3600);
        EquipmentStatusPeriod existing = new EquipmentStatusPeriod(
                EQUIPMENT_ID, "P-01", EquipmentStatus.FAULTY, earlier, later);
        when(periodRepository.findOverlappingForUnit(EQUIPMENT_ID, "P-01", START, END))
                .thenReturn(List.of(existing));

        statusService.addPeriod(EQUIPMENT_ID, "P-01", EquipmentStatus.AVAILABLE, START, END);

        verify(periodRepository).saveAll(argThat(saved ->
                {
                    List<EquipmentStatusPeriod> periods = StreamSupport
                            .stream(saved.spliterator(), false).toList();
                    return periods.size() == 2
                            && periods.get(0).getPeriodStart().equals(earlier)
                            && periods.get(0).getPeriodEnd().equals(START)
                            && periods.get(1).getPeriodStart().equals(END)
                            && periods.get(1).getPeriodEnd().equals(later);
                }));
        verify(periodRepository).delete(existing);
    }

    @Test
    void cannotMarkAReservedUnitFaultyForItsCommittedPeriod() {
        when(periodRepository.findOverlappingForUnit(EQUIPMENT_ID, "P-01", START, END))
                .thenReturn(List.of());
        when(logRepository.findOverlappingForUnit(EQUIPMENT_ID, "P-01", START, END))
                .thenReturn(List.of(new EquipmentLog(
                        EVENT_ID, EQUIPMENT_ID, 1, "P-01", START, END)));

        assertThatThrownBy(() -> statusService.addPeriod(
                EQUIPMENT_ID, "P-01", EquipmentStatus.FAULTY, START, END))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already committed");
    }
}
