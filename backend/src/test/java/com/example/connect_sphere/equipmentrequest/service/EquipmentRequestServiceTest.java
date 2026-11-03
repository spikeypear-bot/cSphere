package com.example.connect_sphere.equipmentrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipment.entity.Equipment;
import com.example.connect_sphere.equipment.repository.EquipmentRepository;
import com.example.connect_sphere.equipment.service.EquipmentReservationService;
import com.example.connect_sphere.equipmentrequest.dto.EquipmentRequestLineResponse;
import com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("unit")
@Tag("EC07")
class EquipmentRequestServiceTest {

    @Mock private EventRepository events;
    @Mock private EquipmentRequestRepository requests;
    @Mock private EquipmentRequestLineRepository lines;
    @Mock private EquipmentRepository equipment;
    @Mock private EquipmentReservationService reservations;

    private EquipmentRequestService service;

    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID EQUIPMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EquipmentRequestService(events, requests, lines, equipment, reservations);
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event(COORDINATOR_ID)));
        when(requests.findByEventId(EVENT_ID)).thenReturn(List.of());
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of());
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requests.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lines.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Equipment catalogueItem = equipment();
        when(equipment.findAllById(any())).thenReturn(List.of(catalogueItem));
    }

    @Test
    void submitCreatesAProcessingRequestAndItsItemLines() {
        var result = service.submit(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("  Two wireless microphones  ",
                        List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 2))));

        assertThat(result.status()).isEqualTo("processing");
        assertThat(result.technicalRequirement()).isEqualTo("Two wireless microphones");
        assertThat(result.lines()).containsExactly(
                new EquipmentRequestLineResponse(EQUIPMENT_ID, "Wireless microphone", 2));
        verify(requests).save(any(EquipmentRequest.class));
        verify(lines).save(any(EquipmentRequestLine.class));
    }

    @Test
    void onlyTheAssignedCoordinatorCanViewOrSubmitARequest() {
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), EVENT_ID,
                new SubmitEquipmentRequest("Microphones", List.of())))
                .isInstanceOf(NotAssignedCoordinatorException.class);
    }

    @Test
    void activeRequestPreventsDuplicateSubmission() {
        EquipmentRequest existing = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.processing, "Microphones");
        when(requests.findByEventId(EVENT_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.submit(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("More microphones", List.of())))
                .isInstanceOf(EquipmentRequestStateException.class);
    }

    @Test
    void rejectsEmptyRequestsAndNonPositiveQuantities() {
        assertThatThrownBy(() -> service.submit(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest(" ", List.of())))
                .isInstanceOf(InvalidEquipmentRequestException.class);
        assertThatThrownBy(() -> service.submit(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("", List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 0)))))
                .isInstanceOf(InvalidEquipmentRequestException.class);
    }

    @Test
    @Tag("EC-NEW3")
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (happy): revising an approved request keeps it and its reservations; the revision waits for Technical Support")
    void reviseKeepsAnApprovedRequestAndItsReservationsUntilTechnicalSupportDecide() {
        EquipmentRequest existing = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.approved, "Two wireless microphones");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(existing));

        var result = service.revise(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("Four wireless microphones now",
                        List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 4))));

        assertThat(result.status()).isEqualTo("processing");
        assertThat(result.revision()).isTrue();
        assertThat(result.lines()).containsExactly(
                new EquipmentRequestLineResponse(EQUIPMENT_ID, "Wireless microphone", 4));
        assertThat(existing.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        verify(reservations, org.mockito.Mockito.never()).releaseAllForEvent(EVENT_ID);
    }

    @Test
    @Tag("EC-NEW3")
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (happy): revising a request still awaiting review replaces it at once and is not a revision of an approved one")
    void revisingAProcessingRequestSupersedesItAndReleasesNow() {
        EquipmentRequest pending = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.processing, "Two wireless microphones");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(pending));

        var result = service.revise(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("Four", List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 4))));

        assertThat(result.revision()).isFalse();
        assertThat(pending.getStatus()).isEqualTo(EquipmentRequestStatus.superseded);
        verify(reservations).releaseAllForEvent(EVENT_ID);
    }

    @Test
    @Tag("EC-NEW3")
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (happy): revising again replaces the pending revision but still keeps the approved request")
    void revisingAgainReplacesOnlyThePendingRevision() {
        EquipmentRequest approved = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.approved, "Two");
        EquipmentRequest pending = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.processing, "Four");
        pending.markRevision();
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(approved, pending));

        var result = service.revise(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("Three", List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 3))));

        assertThat(result.revision()).isTrue();
        assertThat(pending.getStatus()).isEqualTo(EquipmentRequestStatus.superseded);
        assertThat(approved.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        verify(reservations, org.mockito.Mockito.never()).releaseAllForEvent(EVENT_ID);
        verify(reservations).releaseForRequest(pending.getId());
    }

    @Test
    @Tag("EC-NEW3")
    @org.junit.jupiter.api.DisplayName("EC-NEW3 (D37, D40) (happy): revising a request that needs attention keeps it until Technical Support decide")
    void revisingARequestThatNeedsAttentionKeepsItUntilTechnicalSupportDecide() {
        EquipmentRequest flagged = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.approved, "Two wireless microphones");
        flagged.flagAttention("The event's equipment requirements text changed.");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(flagged));

        var result = service.revise(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("Four wireless microphones now",
                        List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 4))));

        assertThat(result.status()).isEqualTo("processing");
        assertThat(flagged.getStatus()).isEqualTo(EquipmentRequestStatus.attention);
        verify(reservations, org.mockito.Mockito.never()).releaseAllForEvent(EVENT_ID);
    }

    @Test
    @Tag("EC-NEW3")
    void reviseWithNoActiveRequestIsRefused() {
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.revise(COORDINATOR_ID, EVENT_ID,
                new SubmitEquipmentRequest("Microphones", List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 1)))))
                .isInstanceOf(EquipmentRequestStateException.class);
    }

    @Test
    @Tag("EC-NEW3")
    void onlyTheAssignedCoordinatorCanRevise() {
        EquipmentRequest existing = new EquipmentRequest(
                UUID.randomUUID(), EVENT_ID, EquipmentRequestStatus.approved, "Microphones");
        when(requests.findByEventIdForUpdate(EVENT_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.revise(UUID.randomUUID(), EVENT_ID,
                new SubmitEquipmentRequest("Microphones", List.of(new SubmitEquipmentRequest.Item(EQUIPMENT_ID, 1)))))
                .isInstanceOf(NotAssignedCoordinatorException.class);
    }

    private static Event event(UUID coordinatorId) {
        Event event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Town Hall");
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        event.setCoordinatorId(coordinatorId);
        event.setStatus(EventStatus.pending);
        return event;
    }

    private static Equipment equipment() {
        Equipment equipment = org.mockito.Mockito.mock(Equipment.class);
        when(equipment.getId()).thenReturn(EQUIPMENT_ID);
        when(equipment.getName()).thenReturn("Wireless microphone");
        return equipment;
    }
}
