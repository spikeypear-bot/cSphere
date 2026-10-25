package com.example.connect_sphere.equipmentrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS09 (decision-log D32): Technical Support's request list by status, as
 * TS01's review queue extended for the console. Repositories are stubs with
 * canned rows; the journey status comes from the real resolver on a fixed
 * clock. The query counts are checked because the list must not issue one
 * query per request.
 */
@Tag("unit")
@Tag("TS09")
@Tag("TS01")
class EquipmentRequestQueueServiceTest {

    private final EquipmentRequestRepository requests = mock(EquipmentRequestRepository.class);
    private final EquipmentRequestLineRepository lines = mock(EquipmentRequestLineRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EquipmentDamageOutcomeRepository outcomes = mock(EquipmentDamageOutcomeRepository.class);
    private final EquipmentRequestQueueService queue = new EquipmentRequestQueueService(requests, lines, events,
            users, outcomes, new JourneyStatusResolver(events, Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"),
                    ZoneOffset.UTC)));

    private final UUID coordinator = UUID.randomUUID();
    private Event later;
    private Event sooner;
    private EquipmentRequest laterRequest;
    private EquipmentRequest soonerRequest;

    private Event event(String name, String start, EventStatus status, UUID coordinatorId) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName(name);
        event.setStartDatetime(OffsetDateTime.parse(start));
        event.setEndDatetime(OffsetDateTime.parse(start).plusHours(3));
        event.setStatus(status);
        event.setCoordinatorId(coordinatorId);
        event.setOrganisation("Acme Pte Ltd");
        return event;
    }

    @BeforeEach
    void setUp() {
        later = event("Later", "2027-03-11T01:00:00Z", EventStatus.confirmed, coordinator);
        sooner = event("Sooner", "2027-03-10T01:00:00Z", EventStatus.pending, null);
        laterRequest = new EquipmentRequest(UUID.randomUUID(), later.getEventId(), EquipmentRequestStatus.processing,
                "Stage");
        soonerRequest = new EquipmentRequest(UUID.randomUUID(), sooner.getEventId(), EquipmentRequestStatus.processing,
                "Lectern");
        when(requests.findByStatusIn(EnumSet.of(EquipmentRequestStatus.processing)))
                .thenReturn(List.of(laterRequest, soonerRequest));
        when(events.findAllById(anyCollection())).thenReturn(List.of(later, sooner));
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(coordinator);
        when(user.getUsername()).thenReturn("ec1");
        when(users.findAllById(anyCollection())).thenReturn(List.of(user));
        Equipment mic = mock(Equipment.class);
        when(mic.getName()).thenReturn("Microphone");
        EquipmentRequestLine line = mock(EquipmentRequestLine.class);
        UUID micId = UUID.randomUUID();
        when(line.getId()).thenReturn(new EquipmentRequestLineId(laterRequest.getId(), micId));
        when(line.getEquipment()).thenReturn(mic);
        when(line.getQuantity()).thenReturn(4);
        when(lines.findByRequestIds(anyCollection())).thenReturn(List.of(line));
        when(outcomes.shortfallRequestIds(anyCollection())).thenReturn(List.of(laterRequest.getId()));
    }

    @Test
    @DisplayName("TS09 (happy): no status means processing, sorted by event start, with requester, items, shortfall flag and journey status")
    void defaultListIsProcessingSoonestFirst() {
        List<EquipmentRequestResponse> listed = queue.list(null);

        assertThat(listed).extracting(EquipmentRequestResponse::requestId)
                .containsExactly(soonerRequest.getId(), laterRequest.getId());
        EquipmentRequestResponse first = listed.get(0);
        assertThat(first.requestedBy()).isNull();
        assertThat(first.items()).isEmpty();
        assertThat(first.isShortfallRequest()).isFalse();
        assertThat(first.eventStatus()).isEqualTo("PLANNING");
        EquipmentRequestResponse second = listed.get(1);
        assertThat(second.requestedBy()).isEqualTo(new EquipmentRequestResponse.Person(coordinator, "ec1"));
        assertThat(second.organisationName()).isEqualTo("Acme Pte Ltd");
        assertThat(second.submittedAt()).isNull();
        assertThat(second.isShortfallRequest()).isTrue();
        assertThat(second.eventStatus()).isEqualTo("CONFIRMED");
        assertThat(second.items()).containsExactly(new EquipmentRequestResponse.Item(
                second.items().get(0).equipmentId(), "Microphone", 4));
        assertThat(second.status()).isEqualTo("processing");
        assertThat(second.eventStart()).isEqualTo(Instant.parse("2027-03-11T01:00:00Z"));
    }

    @Test
    @DisplayName("TS09 (cross-cutting): requests, events, lines, coordinators and shortfall links are each read once, whatever the number of requests")
    void listUsesAFixedNumberOfQueries() {
        queue.list("processing");

        verify(requests, times(1)).findByStatusIn(any());
        verify(events, times(1)).findAllById(anyCollection());
        verify(lines, times(1)).findByRequestIds(anyCollection());
        verify(users, times(1)).findAllById(anyCollection());
        verify(outcomes, times(1)).shortfallRequestIds(anyCollection());
        verify(events, never()).findById(any());
        verify(lines, never()).findByIdRequestId(any());
    }

    @Test
    @DisplayName("TS09 (negative): requests of cancelled or completed events are left out")
    void closedEventsAreLeftOut() {
        later.setStatus(EventStatus.cancelled);
        sooner.setStatus(EventStatus.completed);

        assertThat(queue.list("processing")).isEmpty();
    }

    @Test
    @DisplayName("TS09 (happy): all asks for every status; approved and rejected for one each")
    void statusWordsMapToStatuses() {
        when(requests.findByStatusIn(EnumSet.allOf(EquipmentRequestStatus.class))).thenReturn(List.of(laterRequest));
        when(requests.findByStatusIn(EnumSet.of(EquipmentRequestStatus.rejected))).thenReturn(List.of(soonerRequest));

        assertThat(queue.list("ALL")).extracting(EquipmentRequestResponse::requestId)
                .containsExactly(laterRequest.getId());
        assertThat(queue.list("rejected")).extracting(EquipmentRequestResponse::requestId)
                .containsExactly(soonerRequest.getId());
        assertThat(queue.list("approved")).isEmpty();
    }

}
