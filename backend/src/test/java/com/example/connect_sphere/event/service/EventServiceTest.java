package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

class EventServiceTest {

    @Mock
    private EventRepository repository;
    @Mock
    private EventRequestRepository eventRequestRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;

    private EventService service;

    private static final String ORG = "Acme Conferences";
    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR_ID = UUID.randomUUID();
    private static final UUID ORGANISER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EventService(repository, eventRequestRepository, userRepository, notificationService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void anOrganiserCanViewTheirOwnOrganisationsEvent() {
        Event event = pendingEvent(ORG);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));

        EventDto dto = service.get(ORG, event.getEventId());

        assertThat(dto.eventId()).isEqualTo(event.getEventId());
        assertThat(dto.status()).isEqualTo("pending");
    }

    @Test
    void anOrganiserCannotViewAnotherOrganisationsEvent() {
        Event event = pendingEvent("Other Org Pte Ltd");
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.get(ORG, event.getEventId()))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void confirmingTransitionsToConfirmedAndNotifiesTheOriginatingOrganiser() {
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));
        EventRequest originatingRequest = new EventRequest();
        originatingRequest.setCreatedBy(ORGANISER_ID);
        when(eventRequestRepository.findByEventId(event.getEventId()))
                .thenReturn(Optional.of(originatingRequest));

        EventDto confirmed = service.confirm(COORDINATOR_ID, event.getEventId());

        assertThat(confirmed.status()).isEqualTo("confirmed");
        verify(notificationService).createStatusChangeNotification(
                eq(ORGANISER_ID), isNull(), eq(event.getEventId()), any(), eq("confirmed"), isNull());
    }

    @Test
    void confirmingWithoutBeingTheAssignedCoordinatorIsRejected() {
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.confirm(OTHER_COORDINATOR_ID, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        verify(notificationService, never()).createStatusChangeNotification(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirmingAnAlreadyConfirmedEventIsRejectedSoAtMostOneConfirmationNotificationIsSent() {
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        event.setStatus(EventStatus.confirmed);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.confirm(COORDINATOR_ID, event.getEventId()))
                .isInstanceOf(EventNotPendingException.class);
    }

    @Test
    void confirmingNeverNotifiesWhenNoOriginatingRequestCanBeFound() {
        // Defensive: should never happen in practice (every event comes from
        // an approved request), but must not throw if it somehow does.
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));
        when(eventRequestRepository.findByEventId(event.getEventId())).thenReturn(Optional.empty());

        service.confirm(COORDINATOR_ID, event.getEventId());

        verify(notificationService, never()).createStatusChangeNotification(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void coordinatorContactDetailsAreResolvedOntoTheDto() {
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));
        User coordinator = new User();
        coordinator.setUserId(COORDINATOR_ID);
        coordinator.setUsername("ec1");
        coordinator.setEmail("ec1@connectsphere.test");
        coordinator.setRole(UserRole.ec);
        coordinator.setHashedPassword("irrelevant");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        EventDto dto = service.getForCoordinator(event.getEventId());

        assertThat(dto.coordinatorName()).isEqualTo("ec1");
        assertThat(dto.coordinatorEmail()).isEqualTo("ec1@connectsphere.test");
    }

    private static Event pendingEvent(String organisation) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Q1 Town Hall");
        event.setPurpose("All-hands update");
        event.setStartDatetime(OffsetDateTime.now().plusDays(30));
        event.setEndDatetime(OffsetDateTime.now().plusDays(30).plusHours(2));
        event.setExpectedAttendance(150);
        event.setVenueRequirements("Theatre-style seating for 150");
        event.setOrganisation(organisation);
        event.setStatus(EventStatus.pending);
        return event;
    }
}
