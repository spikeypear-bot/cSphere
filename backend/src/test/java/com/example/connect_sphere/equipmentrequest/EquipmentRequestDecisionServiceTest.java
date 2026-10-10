package com.example.connect_sphere.equipmentrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class EquipmentRequestDecisionServiceTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID EQUIPMENT_ID = UUID.randomUUID();
    private static final Instant EVENT_START = Instant.parse("2027-03-10T01:00:00Z");
    private static final Instant EVENT_END = Instant.parse("2027-03-10T04:00:00Z");

    private EquipmentRequestRepository requests;
    private EquipmentRequestLineRepository lines;
    private EventRepository events;
    private EquipmentLogRepository reservations;
    private EquipmentRequestDecisionService service;
    private EquipmentRequest request;

    @BeforeEach
    void setUp() {
        requests = mock(EquipmentRequestRepository.class);
        lines = mock(EquipmentRequestLineRepository.class);
        events = mock(EventRepository.class);
        reservations = mock(EquipmentLogRepository.class);
        service = new EquipmentRequestDecisionService(requests, lines, events, reservations);

        request = new EquipmentRequest(
                REQUEST_ID, EVENT_ID, EquipmentRequestStatus.processing, "Stage setup");
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        Event event = new Event();
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        EquipmentRequestLine line = requestLine(2);
        when(lines.findByIdRequestId(REQUEST_ID)).thenReturn(List.of(line));
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of());
    }

    @Test
    void cannotApproveUntilEveryRequestedUnitIsReservedForTheFullEvent() {
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of(
                reservation(1, EVENT_START, EVENT_END)));

        assertThatThrownBy(() -> service.updateStatus(
                REQUEST_ID, new UpdateEquipmentRequestStatus("approved", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only 1 of 2 Projector");

        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.processing);
        verify(requests, never()).save(request);
        verify(reservations, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reservationOutsideTheWholeEventPeriodDoesNotCountTowardApproval() {
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of(
                reservation(2, EVENT_START.plusSeconds(60), EVENT_END)));

        assertThatThrownBy(() -> service.updateStatus(
                REQUEST_ID, new UpdateEquipmentRequestStatus("approved", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only 0 of 2 Projector");
        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.processing);
    }

    @Test
    void fullyReservedRequestCanBeApprovedWithoutChangingReservationsOrEvent() {
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of(
                reservation(2, EVENT_START, EVENT_END)));

        EquipmentRequestDetailsResponse response = service.updateStatus(
                REQUEST_ID, new UpdateEquipmentRequestStatus("approved", null));

        assertThat(response.status()).isEqualTo("approved");
        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        verify(reservations).findByEventId(EVENT_ID);
        verify(reservations, never()).save(org.mockito.ArgumentMatchers.any());
        verify(events, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectionRequiresAndStoresAReason() {
        assertThatThrownBy(() -> service.updateStatus(
                REQUEST_ID, new UpdateEquipmentRequestStatus("rejected", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("A reason is required");
        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.processing);

        EquipmentRequestDetailsResponse response = service.updateStatus(
                REQUEST_ID, new UpdateEquipmentRequestStatus("rejected", "  Not suitable  "));

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.rejectReason()).isEqualTo("Not suitable");
        assertThat(request.getRejectReason()).isEqualTo("Not suitable");
    }

    private static EquipmentRequestLine requestLine(int quantity) {
        Equipment equipment = mock(Equipment.class);
        when(equipment.getName()).thenReturn("Projector");
        EquipmentRequestLine line = mock(EquipmentRequestLine.class);
        when(line.getId()).thenReturn(new EquipmentRequestLineId(REQUEST_ID, EQUIPMENT_ID));
        when(line.getEquipment()).thenReturn(equipment);
        when(line.getQuantity()).thenReturn(quantity);
        return line;
    }

    private static EquipmentLog reservation(int quantity, Instant from, Instant until) {
        return new EquipmentLog(EVENT_ID, EQUIPMENT_ID, quantity, null, from, until);
    }
}
