package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.dto.EventCancellationRequestDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventCancellationRequest;
import com.example.connect_sphere.event.entity.EventCancellationStatus;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventCancellationRequestRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO07 with every collaborator doubled and the clock fixed. Expected values
 * come from the acceptance criteria (numbered as the user gave them).
 * Notifications are mocks because the requirement is the interaction: the
 * right person, exactly once, and never when the action fails.
 */
@Tag("unit")
@Tag("EO07")
class EventCancellationServiceTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final String ORG = "Acme Pte Ltd";
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final String REASON = "The client has called the event off.";

    @Mock private EventRepository events;
    @Mock private EventCancellationRequestRepository cancellations;
    @Mock private UserRepository users;
    @Mock private EventRequestRepository eventRequests;
    @Mock private EventRequestMapper requestMapper;
    @Mock private ActivityService activity;
    @Mock private NotificationService notifications;
    @Mock private EventLifecycleService lifecycle;

    private EventCancellationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EventCancellationService(events, cancellations, users, eventRequests, requestMapper, activity,
                notifications, new JourneyStatusResolver(events, clock), lifecycle, clock);
        when(eventRequests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cancellations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User organiser = new User();
        organiser.setUserId(ORGANISER);
        organiser.setUsername("eo1");
        when(users.findById(ORGANISER)).thenReturn(Optional.of(organiser));
    }

    /** An approved request and its event, in {@code status}, assigned to COORDINATOR. */
    private Event approvedEvent(EventStatus status) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setOrganisation(ORG);
        event.setCoordinatorId(COORDINATOR);
        event.setStatus(status);
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        EventRequest origin = request(EventRequestStatus.approved, COORDINATOR);
        origin.setEventId(event.getEventId());
        when(eventRequests.findById(origin.getRequestId())).thenReturn(Optional.of(origin));
        when(eventRequests.findByEventId(event.getEventId())).thenReturn(Optional.of(origin));
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private UUID requestIdOf(Event event) {
        return eventRequests.findByEventId(event.getEventId()).orElseThrow().getRequestId();
    }

    private EventRequest request(EventRequestStatus status, UUID coordinator) {
        EventRequest request = new EventRequest();
        request.setRequestId(UUID.randomUUID());
        request.setOrganisation(ORG);
        request.setStatus(status);
        request.setCoordinatorId(coordinator);
        request.setEventName("Town Hall");
        request.setCreatedBy(ORGANISER);
        when(eventRequests.findForUpdate(request.getRequestId())).thenReturn(Optional.of(request));
        return request;
    }

    // ---- AC2: before approval the organiser cancels at once ------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventRequestStatus.class, names = {"pending", "clarification_required"})
    @DisplayName("EO07 AC2, AC11 (happy): a Submitted, Under Review or Clarification Required request becomes Cancelled at once, with the reason on its timeline")
    void aRequestUnderReviewIsCancelledAtOnce(EventRequestStatus status) {
        EventRequest request = request(status, COORDINATOR);

        service.cancelRequest(ORG, ORGANISER, request.getRequestId(), "  " + REASON + "  ");

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.cancelled);
        assertThat(request.getUpdatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(eventRequests).save(request);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.request_cancelled);
        assertThat(entry.getValue().type().major()).isTrue();
        assertThat(entry.getValue().actorUserId()).isEqualTo(ORGANISER);
        assertThat(entry.getValue().message()).isEqualTo(REASON);
        assertThat(entry.getValue().fromStatus()).isEqualTo(status.name());
        assertThat(entry.getValue().toStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("EO07 AC2 (happy): a Submitted request with no coordinator yet is cancelled and nobody is notified")
    void aSubmittedRequestWithNoCoordinatorIsCancelledWithoutNotifyingAnyone() {
        EventRequest request = request(EventRequestStatus.pending, null);

        service.cancelRequest(ORG, ORGANISER, request.getRequestId(), REASON);

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.cancelled);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EO07 AC2 (happy): the coordinator reviewing the request is told it was cancelled, with the reason, exactly once")
    void theAssignedCoordinatorIsNotifiedOnce() {
        EventRequest request = request(EventRequestStatus.pending, COORDINATOR);

        service.cancelRequest(ORG, ORGANISER, request.getRequestId(), REASON);

        verify(notifications, times(1)).createRequestCancelledNotification(
                COORDINATOR, request.getRequestId(), "Town Hall", REASON);
    }

    // ---- AC5: not from Draft, Rejected, Cancelled or Approved ---------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventRequestStatus.class, names = {"draft", "rejected", "cancelled", "approved"})
    @DisplayName("EO07 AC5 (negative): a Draft, Rejected, Cancelled or approved request cannot be cancelled this way, and nothing changes")
    void aRequestOutsideReviewCannotBeCancelled(EventRequestStatus status) {
        EventRequest request = request(status, COORDINATOR);

        assertThatThrownBy(() -> service.cancelRequest(ORG, ORGANISER, request.getRequestId(), REASON))
                .isInstanceOf(EventCancellationStateException.class)
                .hasMessageContaining("Only a request that is Submitted, Under Review or Clarification Required");

        assertThat(request.getStatus()).isEqualTo(status);
        verify(eventRequests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    // ---- AC1: only the owning organisation -----------------------------------

    @Test
    @DisplayName("EO07 AC1 (negative): an organiser of another organisation is told the request does not exist, and nothing changes")
    void anotherOrganisationsOrganiserIsRefused() {
        EventRequest request = request(EventRequestStatus.pending, COORDINATOR);

        assertThatThrownBy(() -> service.cancelRequest("Globex Holdings", UUID.randomUUID(),
                request.getRequestId(), REASON)).isInstanceOf(EventRequestNotFoundException.class);

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        verify(eventRequests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    // ---- AC4: a reason of 1–2000 characters ------------------------------------

    @Test
    @DisplayName("EO07 AC4 (negative): a blank reason is refused with a clear message and nothing is saved")
    void aBlankReasonIsRefused() {
        EventRequest request = request(EventRequestStatus.pending, COORDINATOR);

        assertThatThrownBy(() -> service.cancelRequest(ORG, ORGANISER, request.getRequestId(), "   "))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Please give a reason for cancelling this request.");

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        verify(eventRequests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC4 (boundary): a reason of exactly 2000 characters is accepted")
    void aReasonOfExactly2000CharactersIsAccepted() {
        EventRequest request = request(EventRequestStatus.pending, null);

        service.cancelRequest(ORG, ORGANISER, request.getRequestId(), "r".repeat(2000));

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.cancelled);
    }

    @Test
    @DisplayName("EO07 AC4 (boundary): a reason of 2001 characters is refused and nothing is saved")
    void aReasonOf2001CharactersIsRefused() {
        EventRequest request = request(EventRequestStatus.pending, COORDINATOR);

        assertThatThrownBy(() -> service.cancelRequest(ORG, ORGANISER, request.getRequestId(), "r".repeat(2001)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("at most 2000 characters");

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        verify(eventRequests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    // ---- AC14: a failed action changes nothing and tells no one -------------

    @Test
    @DisplayName("EO07 AC14 (failure): if saving fails, no timeline entry is written and nobody is notified")
    void aFailedSaveSendsNoNotification() {
        EventRequest request = request(EventRequestStatus.pending, COORDINATOR);
        when(eventRequests.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.cancelRequest(ORG, ORGANISER, request.getRequestId(), REASON))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activity);
        verify(notifications, never()).createRequestCancelledNotification(any(), any(), anyString(), anyString());
    }

    // ---- AC3: after approval the organiser requests cancellation ------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class,
            names = {"pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved", "confirmed"})
    @DisplayName("EO07 AC3, AC6, AC11 (happy): requesting cancellation of a Planning to Confirmed event saves a pending request with the reason, and the event keeps its status")
    void requestingCancellationSavesAPendingRequestAndLeavesTheEventAlone(EventStatus status) {
        Event event = approvedEvent(status);

        EventCancellationRequestDto dto = service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON);

        ArgumentCaptor<EventCancellationRequest> saved = ArgumentCaptor.forClass(EventCancellationRequest.class);
        verify(cancellations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(EventCancellationStatus.pending);
        assertThat(saved.getValue().getEventId()).isEqualTo(event.getEventId());
        assertThat(saved.getValue().getRequesterUserId()).isEqualTo(ORGANISER);
        assertThat(saved.getValue().getReason()).isEqualTo(REASON);
        assertThat(saved.getValue().getRequestedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(dto.status()).isEqualTo(EventCancellationStatus.pending);
        assertThat(dto.requesterName()).isEqualTo("eo1");
        assertThat(dto.reason()).isEqualTo(REASON);
        // AC3: the event and its arrangements are untouched until a decision.
        assertThat(event.getStatus()).isEqualTo(status);
        verify(events, never()).save(any());
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.event_cancellation_requested);
        assertThat(entry.getValue().type().major()).isTrue();
        assertThat(entry.getValue().eventId()).isEqualTo(event.getEventId());
        assertThat(entry.getValue().message()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("EO07 AC7 (happy): the assigned coordinator is notified of the request, with the reason, exactly once")
    void theAssignedCoordinatorIsNotifiedOfTheRequestOnce() {
        Event event = approvedEvent(EventStatus.confirmed);

        service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON);

        verify(notifications, times(1)).createEventCancellationRequestedNotification(
                COORDINATOR, event.getEventId(), "Town Hall", REASON);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"cancelled", "completed"})
    @DisplayName("EO07 AC5 (negative): a Cancelled or Completed event cannot have cancellation requested, and nothing changes")
    void aFinishedEventCannotHaveCancellationRequested(EventStatus status) {
        Event event = approvedEvent(status);

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON))
                .isInstanceOf(EventCancellationStateException.class)
                .hasMessageContaining("Only an event in Planning");

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC5 (boundary): a Confirmed event whose end has passed is Completed and cannot have cancellation requested")
    void aConfirmedEventThatHasEndedIsCompleted() {
        Event event = approvedEvent(EventStatus.confirmed);
        event.setStartDatetime(OffsetDateTime.parse("2026-12-31T20:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2026-12-31T23:59:59Z"));

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON))
                .isInstanceOf(EventCancellationStateException.class)
                .hasMessageContaining("this one is Completed");

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventRequestStatus.class, names = {"draft", "rejected", "cancelled", "pending"})
    @DisplayName("EO07 AC5 (negative): a request that was never approved has no event to request cancellation of, and nothing changes")
    void aRequestThatWasNeverApprovedHasNoEventToCancel(EventRequestStatus status) {
        EventRequest request = request(status, COORDINATOR);
        when(eventRequests.findById(request.getRequestId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, request.getRequestId(), REASON))
                .isInstanceOf(EventCancellationStateException.class);

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC6 (conflict): a second request while one is pending is refused, and nothing is saved")
    void aSecondRequestWhileOneIsPendingIsRefused() {
        Event event = approvedEvent(EventStatus.confirmed);
        when(cancellations.existsByEventIdAndStatus(event.getEventId(), EventCancellationStatus.pending))
                .thenReturn(true);

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON))
                .isInstanceOf(EventCancellationStateException.class)
                .hasMessage("A cancellation request for this event is already waiting for the coordinator's decision.");

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC1 (negative): an organiser of another organisation cannot request cancellation, and nothing changes")
    void anotherOrganisationCannotRequestCancellation() {
        Event event = approvedEvent(EventStatus.confirmed);

        assertThatThrownBy(() -> service.requestCancellation("Globex Holdings", UUID.randomUUID(),
                requestIdOf(event), REASON)).isInstanceOf(EventRequestNotFoundException.class);

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC4 (negative): requesting cancellation with a blank reason is refused and nothing is saved")
    void requestingCancellationNeedsAReason() {
        Event event = approvedEvent(EventStatus.pending);

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, requestIdOf(event), ""))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Please give a reason for cancelling this event.");

        verify(cancellations, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC14 (failure): if saving the request fails, nobody is notified")
    void aFailedRequestSendsNoNotification() {
        Event event = approvedEvent(EventStatus.confirmed);
        when(cancellations.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.requestCancellation(ORG, ORGANISER, requestIdOf(event), REASON))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activity, notifications);
    }

    // ---- Who may read the requests (AC6, AC7) --------------------------------

    @Test
    @DisplayName("EO07 AC6, AC7 (happy): the organiser, the assigned coordinator and the Lead see the pending request with its reason and when it was asked")
    void theOrganiserCoordinatorAndLeadSeeThePendingRequest() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest pending = new EventCancellationRequest();
        pending.setCancellationRequestId(UUID.randomUUID());
        pending.setEventId(event.getEventId());
        pending.setRequesterUserId(ORGANISER);
        pending.setReason(REASON);
        pending.setStatus(EventCancellationStatus.pending);
        pending.setRequestedAt(OffsetDateTime.parse("2026-12-30T10:00:00Z"));
        when(cancellations.findByEventIdOrderByRequestedAtDesc(event.getEventId())).thenReturn(List.of(pending));

        for (List<EventCancellationRequestDto> seen : List.of(
                service.forEvent(UserRole.eo, ORG, ORGANISER, event.getEventId()),
                service.forEvent(UserRole.ec, null, COORDINATOR, event.getEventId()),
                service.forEvent(UserRole.ecl, null, UUID.randomUUID(), event.getEventId()))) {
            assertThat(seen).singleElement().satisfies(dto -> {
                assertThat(dto.status()).isEqualTo(EventCancellationStatus.pending);
                assertThat(dto.reason()).isEqualTo(REASON);
                assertThat(dto.requestedAt()).isEqualTo(OffsetDateTime.parse("2026-12-30T10:00:00Z"));
                assertThat(dto.requesterName()).isEqualTo("eo1");
            });
        }
    }

    @Test
    @DisplayName("EO07 AC1, AC7 (negative): another organisation's organiser, a coordinator who is not assigned, and other roles cannot read the requests")
    void outsidersCannotReadTheRequests() {
        Event event = approvedEvent(EventStatus.confirmed);

        assertThatThrownBy(() -> service.forEvent(UserRole.eo, "Globex Holdings", UUID.randomUUID(), event.getEventId()))
                .isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.forEvent(UserRole.ec, null, UUID.randomUUID(), event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThatThrownBy(() -> service.forEvent(UserRole.vs, null, UUID.randomUUID(), event.getEventId()))
                .isInstanceOf(EventNotFoundException.class);
    }

    // ---- AC7, AC8, AC9, AC13: the assigned coordinator decides ----------------

    private EventCancellationRequest pendingRequest(Event event) {
        EventCancellationRequest request = new EventCancellationRequest();
        request.setCancellationRequestId(UUID.randomUUID());
        request.setEventId(event.getEventId());
        request.setRequesterUserId(ORGANISER);
        request.setReason(REASON);
        request.setStatus(EventCancellationStatus.pending);
        request.setRequestedAt(OffsetDateTime.parse("2026-12-30T10:00:00Z"));
        when(cancellations.findForUpdate(request.getCancellationRequestId())).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    @DisplayName("EO07 AC8 (happy): approving cancels the event through the lifecycle, records the decision, and tells the organiser exactly once")
    void approvingCancelsTheEventAndTellsTheOrganiser() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);

        EventCancellationRequestDto dto = service.approve(COORDINATOR, event.getEventId(),
                request.getCancellationRequestId(), null);

        verify(lifecycle, times(1)).cancel(COORDINATOR, event.getEventId(),
                "Cancelled at the organiser's request: " + REASON);
        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.approved);
        assertThat(request.getDecidedBy()).isEqualTo(COORDINATOR);
        assertThat(request.getDecidedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(dto.status()).isEqualTo(EventCancellationStatus.approved);
        verify(notifications, times(1)).createEventCancellationDecisionNotification(ORGANISER, event.getEventId(),
                "Town Hall", NotificationType.event_cancellation_approved, "Town Hall has been cancelled as you asked.");
    }

    @Test
    @DisplayName("EO07 AC9, AC10, AC11 (happy): declining needs a reason, leaves the event alone, is recorded as Major, and tells the organiser the reason exactly once")
    void decliningKeepsTheEventAndTellsTheOrganiserWhy() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);

        service.decline(COORDINATOR, event.getEventId(), request.getCancellationRequestId(), "The venue is non-refundable");

        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.declined);
        assertThat(request.getDecisionReason()).isEqualTo("The venue is non-refundable");
        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        verifyNoInteractions(lifecycle);
        verify(events, never()).save(any());
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.event_cancellation_declined);
        assertThat(entry.getValue().type().major()).isTrue();
        assertThat(entry.getValue().actorUserId()).isEqualTo(COORDINATOR);
        assertThat(entry.getValue().message()).isEqualTo("The venue is non-refundable");
        verify(notifications, times(1)).createEventCancellationDecisionNotification(ORGANISER, event.getEventId(),
                "Town Hall", NotificationType.event_cancellation_declined,
                "Cancellation declined: The venue is non-refundable");
    }

    @Test
    @DisplayName("EO07 AC9 (negative): declining without a reason is refused and nothing changes")
    void decliningWithoutAReasonIsRefused() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);

        assertThatThrownBy(() -> service.decline(COORDINATOR, event.getEventId(),
                request.getCancellationRequestId(), "  "))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Please give the organiser a reason for declining.");

        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.pending);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(lifecycle, activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC7 (negative): a coordinator who is not assigned (or the Lead) cannot approve or decline, and nothing changes")
    void onlyTheAssignedCoordinatorDecides() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.approve(stranger, event.getEventId(), request.getCancellationRequestId(), null))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThatThrownBy(() -> service.decline(stranger, event.getEventId(), request.getCancellationRequestId(), "No"))
                .isInstanceOf(NotAssignedCoordinatorException.class);

        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.pending);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(lifecycle, activity, notifications);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventCancellationStatus.class, names = {"approved", "declined"})
    @DisplayName("EO07 AC13 (conflict): a request that was already decided cannot be decided again; nothing changes and nobody is notified")
    void anAlreadyDecidedRequestIsRefused(EventCancellationStatus decided) {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);
        request.setStatus(decided);

        assertThatThrownBy(() -> service.approve(COORDINATOR, event.getEventId(), request.getCancellationRequestId(), null))
                .isInstanceOf(EventCancellationStateException.class)
                .hasMessageContaining("already been " + decided.name());
        assertThatThrownBy(() -> service.decline(COORDINATOR, event.getEventId(), request.getCancellationRequestId(), "No"))
                .isInstanceOf(EventCancellationStateException.class);

        verify(cancellations, never()).save(any());
        verifyNoInteractions(lifecycle, activity, notifications);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"cancelled", "completed"})
    @DisplayName("EO07 AC13 (conflict): once the event was cancelled or completed, a late decision is refused and nothing changes")
    void aDecisionOnAFinishedEventIsRefused(EventStatus status) {
        Event event = approvedEvent(status);
        EventCancellationRequest request = pendingRequest(event);

        assertThatThrownBy(() -> service.approve(COORDINATOR, event.getEventId(), request.getCancellationRequestId(), null))
                .isInstanceOf(EventCancellationStateException.class);
        assertThatThrownBy(() -> service.decline(COORDINATOR, event.getEventId(), request.getCancellationRequestId(), "No"))
                .isInstanceOf(EventCancellationStateException.class);

        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.pending);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(lifecycle, activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC13 (negative): a request that belongs to another event is not found, and nothing changes")
    void aRequestOfAnotherEventIsNotFound() {
        Event event = approvedEvent(EventStatus.confirmed);
        Event other = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest otherRequest = pendingRequest(other);

        assertThatThrownBy(() -> service.approve(COORDINATOR, event.getEventId(),
                otherRequest.getCancellationRequestId(), null))
                .isInstanceOf(EventCancellationNotFoundException.class);

        verifyNoInteractions(lifecycle, activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC14 (failure): if cancelling the event fails, the request stays pending and nobody is notified")
    void aFailedApprovalSendsNoNotification() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);
        when(lifecycle.cancel(any(), any(), anyString())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.approve(COORDINATOR, event.getEventId(),
                request.getCancellationRequestId(), null)).isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(EventCancellationStatus.pending);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EO07 AC14 (failure): if saving a decline fails, nobody is notified")
    void aFailedDeclineSendsNoNotification() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);
        when(cancellations.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.decline(COORDINATOR, event.getEventId(),
                request.getCancellationRequestId(), "No")).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC4 (boundary): an approval note over 2000 characters is refused and nothing changes")
    void anOverLongApprovalNoteIsRefused() {
        Event event = approvedEvent(EventStatus.confirmed);
        EventCancellationRequest request = pendingRequest(event);

        assertThatThrownBy(() -> service.approve(COORDINATOR, event.getEventId(),
                request.getCancellationRequestId(), "n".repeat(2001))).isInstanceOf(InvalidMessageException.class);

        verifyNoInteractions(lifecycle, activity, notifications);
    }
}
