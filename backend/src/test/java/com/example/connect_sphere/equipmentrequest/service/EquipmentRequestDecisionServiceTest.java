package com.example.connect_sphere.equipmentrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipment.entity.Equipment;
import com.example.connect_sphere.equipment.entity.EquipmentLog;
import com.example.connect_sphere.equipment.repository.EquipmentLogRepository;
import com.example.connect_sphere.equipmentrequest.dto.EquipmentRequestDetailsResponse;
import com.example.connect_sphere.equipmentrequest.dto.UpdateEquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestLineId;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestRepository;
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
@Tag("TS06")
@Tag("EC-NEW3")
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
    private com.example.connect_sphere.equipment.service.EquipmentReservationService releaser;
    private EquipmentRequestDecisionService service;
    private EquipmentRequest request;

    @BeforeEach
    void setUp() {
        requests = mock(EquipmentRequestRepository.class);
        lines = mock(EquipmentRequestLineRepository.class);
        events = mock(EventRepository.class);
        reservations = mock(EquipmentLogRepository.class);
        releaser = mock(com.example.connect_sphere.equipment.service.EquipmentReservationService.class);
        service = new EquipmentRequestDecisionService(requests, lines, events, reservations, releaser);

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
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (happy): approving a revision supersedes the approved request and keeps only what the revision needs")
    void approvingARevisionReplacesTheApprovedRequest() {
        request.markRevision();
        EquipmentRequest replaced = new EquipmentRequest(UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.attention, "Old");
        EquipmentRequest rejectedEarlier = new EquipmentRequest(UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.rejected, "Older");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(replaced, rejectedEarlier, request));
        events.findById(EVENT_ID).orElseThrow().setEventId(EVENT_ID);
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of(reservation(2, EVENT_START, EVENT_END)));

        service.updateStatus(REQUEST_ID, new UpdateEquipmentRequestStatus("approved", null));

        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        assertThat(replaced.getStatus()).isEqualTo(EquipmentRequestStatus.superseded);
        assertThat(rejectedEarlier.getStatus()).isEqualTo(EquipmentRequestStatus.rejected);
        verify(releaser).keepOnly(EVENT_ID, REQUEST_ID, java.util.Map.of(EQUIPMENT_ID, 2), EVENT_START, EVENT_END);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (negative): rejecting a revision gives back only its own reservations and leaves the approved request alone")
    void rejectingARevisionReleasesNothing() {
        request.markRevision();
        EquipmentRequest approved = new EquipmentRequest(UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.approved, "Old");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(approved, request));

        service.updateStatus(REQUEST_ID, new UpdateEquipmentRequestStatus("rejected", "Not enough stock"));

        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.rejected);
        assertThat(approved.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        verify(releaser).releaseForRequest(REQUEST_ID);
        verify(releaser, never()).keepOnly(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TS06 (happy): approving a request that is not a revision replaces nothing")
    void approvingANonRevisionReplacesNothing() {
        when(reservations.findByEventId(EVENT_ID)).thenReturn(List.of(reservation(2, EVENT_START, EVENT_END)));

        service.updateStatus(REQUEST_ID, new UpdateEquipmentRequestStatus("approved", null));

        verify(requests, never()).findByEventIdForUpdate(org.mockito.ArgumentMatchers.any());
        verify(releaser, never()).keepOnly(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"attention", "superseded"})
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (D37) (negative): Technical Support can only approve or reject, not set a coordinator-side status")
    void technicalSupportCannotSetACoordinatorSideStatus(String status) {
        assertThatThrownBy(() -> service.updateStatus(REQUEST_ID, new UpdateEquipmentRequestStatus(status, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("status must be approved or rejected");

        assertThat(request.getStatus()).isEqualTo(EquipmentRequestStatus.processing);
        verify(requests, never()).save(request);
    }
}
