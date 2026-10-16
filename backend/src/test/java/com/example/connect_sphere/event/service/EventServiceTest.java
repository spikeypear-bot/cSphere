package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;

@Tag("unit")
@Tag("EC09")
@Tag("EC08")
@Tag("EO09")
class EventServiceTest {

    @Mock
    private EventRequestRepository eventRequestRepository;
    @Mock
    private EventRepository repository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EventLifecycleService lifecycle;

    private EventService service;

    private static final String ORG = "Acme Conferences";
    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EventService(repository, eventRequestRepository, userRepository, notificationService,
                new JourneyStatusResolver(repository), lifecycle);
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

    // Confirmation itself (safety gate, re-checks, notifications, double
    // clicks) moved to EventLifecycleService with EC08 / Week 7 Change #6 and
    // is tested in EventLifecycleServiceTest. EO09's original "pending ->
    // confirmed" shortcut no longer exists.

    @Test
    void confirmingDelegatesToTheLifecycleAndReturnsTheUpdatedEvent() {
        Event event = pendingEvent(ORG);
        event.setStatus(EventStatus.confirmed);
        when(repository.findById(event.getEventId())).thenReturn(Optional.of(event));

        EventDto confirmed = service.confirm(COORDINATOR_ID, event.getEventId());

        verify(lifecycle).confirm(COORDINATOR_ID, event.getEventId());
        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.journeyStatus().label()).isEqualTo("Confirmed");
    }

    @Test
    void aRefusedConfirmationPropagatesAndNotifiesNoOne() {
        Event event = pendingEvent(ORG);
        when(lifecycle.confirm(OTHER_COORDINATOR_ID, event.getEventId()))
                .thenThrow(new NotAssignedCoordinatorException(event.getEventId()));

        assertThatThrownBy(() -> service.confirm(OTHER_COORDINATOR_ID, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
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

    // ---- EC09: events assigned to me --------------------------------------

    @Test
    void assignedEventsAreReadForTheCallerOnlyAndOnlyWhileStillActive() {
        when(repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(any(), any())).thenReturn(List.of());

        service.assignedEvents(COORDINATOR_ID);

        ArgumentCaptor<Collection<EventStatus>> statuses = ArgumentCaptor.captor();
        verify(repository).findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(
                eq(COORDINATOR_ID), statuses.capture());
        // EC08: events in safety review are still the coordinator's to plan.
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(EventStatus.pending,
                EventStatus.awaiting_safety_review, EventStatus.safety_changes_requested,
                EventStatus.safety_approved, EventStatus.confirmed);
    }

    @Test
    void assignedEventsKeepTheirSoonestFirstOrderAndCarryWhatTheListShows() {
        Event sooner = pendingEvent(ORG);
        sooner.setEventName("Product Launch");
        sooner.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        sooner.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        sooner.setExpectedAttendance(80);
        sooner.setStatus(EventStatus.confirmed);
        Event later = pendingEvent("Globex Holdings");
        when(repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(any(), any()))
                .thenReturn(List.of(sooner, later));

        List<EventDto> events = service.assignedEvents(COORDINATOR_ID);

        assertThat(events).extracting(EventDto::eventId)
                .containsExactly(sooner.getEventId(), later.getEventId());
        EventDto first = events.get(0);
        assertThat(first.eventName()).isEqualTo("Product Launch");
        assertThat(first.organisation()).isEqualTo(ORG);
        assertThat(first.startDatetime()).isEqualTo(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        assertThat(first.endDatetime()).isEqualTo(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        assertThat(first.expectedAttendance()).isEqualTo(80);
        assertThat(first.status()).isEqualTo("confirmed");
        assertThat(events.get(1).organisation()).isEqualTo("Globex Holdings");
        assertThat(events.get(1).status()).isEqualTo("pending");
    }

    @Test
    void aCoordinatorWithNoActiveEventsGetsAnEmptyList() {
        when(repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(any(), any())).thenReturn(List.of());

        assertThat(service.assignedEvents(COORDINATOR_ID)).isEmpty();
    }

    @Test
    void listingAssignedEventsIsReadOnly() {
        Event event = pendingEvent(ORG);
        event.setCoordinatorId(COORDINATOR_ID);
        when(repository.findByCoordinatorIdAndStatusInOrderByStartDatetimeAsc(any(), any()))
                .thenReturn(List.of(event));

        service.assignedEvents(COORDINATOR_ID);

        verify(repository, never()).save(any());
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        assertThat(event.getCoordinatorId()).isEqualTo(COORDINATOR_ID);
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
