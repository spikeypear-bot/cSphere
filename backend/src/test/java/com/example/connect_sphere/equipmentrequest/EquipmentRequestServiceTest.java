package com.example.connect_sphere.equipmentrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class EquipmentRequestServiceTest {

    @Mock private EventRepository events;
    @Mock private EquipmentRequestRepository requests;
    @Mock private EquipmentRequestLineRepository lines;
    @Mock private EquipmentRepository equipment;

    private EquipmentRequestService service;

    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID EQUIPMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EquipmentRequestService(events, requests, lines, equipment);
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event(COORDINATOR_ID)));
        when(requests.findByEventId(EVENT_ID)).thenReturn(List.of());
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
