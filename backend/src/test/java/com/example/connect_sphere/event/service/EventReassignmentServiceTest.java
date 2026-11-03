package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventReassignmentRequest;
import com.example.connect_sphere.event.entity.EventReassignmentStatus;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventReassignmentRequestRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
@Tag("EC-C1")
@Tag("EC-NEW4")
class EventReassignmentServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID NEXT_COORDINATOR_ID = UUID.randomUUID();
    private static final UUID LEAD_ID = UUID.randomUUID();
    private static final UUID ORGANISER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    @Mock private EventRepository events;
    @Mock private EventReassignmentRequestRepository requests;
    @Mock private EventRequestRepository eventRequests;
    @Mock private UserRepository users;
    @Mock private ActivityService activity;
    @Mock private NotificationService notifications;

    private EventReassignmentService service;
    private Event event;
    private EventRequest origin;
    private User coordinator;
    private User nextCoordinator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EventReassignmentService(events, requests, eventRequests, users, activity, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC));
        event = event(EventStatus.confirmed);
        origin = new EventRequest();
        origin.setRequestId(REQUEST_ID);
        origin.setEventId(EVENT_ID);
        origin.setCoordinatorId(COORDINATOR_ID);
        origin.setCreatedBy(ORGANISER_ID);
        coordinator = user(COORDINATOR_ID, UserRole.ec, "ec1");
        nextCoordinator = user(NEXT_COORDINATOR_ID, UserRole.ec, "ec2");

        when(events.save(any())).thenAnswer(call -> call.getArgument(0));
        when(eventRequests.save(any())).thenAnswer(call -> call.getArgument(0));
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        when(eventRequests.findByEventId(EVENT_ID)).thenReturn(Optional.of(origin));
        when(eventRequests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(origin));
    }

    @Test
    @DisplayName("EC-C1 (happy): the assigned coordinator submits a reason and the Lead is notified")
    void assignedCoordinatorCanSubmitReasonAndLeadReceivesNotification() {
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(requests.existsByEventIdAndStatus(EVENT_ID, EventReassignmentStatus.pending)).thenReturn(false);
        when(users.findByRole(UserRole.ecl)).thenReturn(List.of(user(LEAD_ID, UserRole.ecl, "ecl1")));
        when(users.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        var result = service.request(COORDINATOR_ID, EVENT_ID, "  I am no longer available.  ");

        assertThat(result.status()).isEqualTo(EventReassignmentStatus.pending);
        assertThat(result.reason()).isEqualTo("I am no longer available.");
        assertThat(result.requestedAt().toInstant()).isEqualTo(NOW);
        verify(requests).save(any(EventReassignmentRequest.class));
        verify(activity).record(org.mockito.ArgumentMatchers.argThat(entry ->
                entry.type() == ActivityType.event_reassignment_requested
                        && entry.message().equals("I am no longer available.")));
        verify(notifications).createEventReassignmentRequestedNotifications(
                List.of(LEAD_ID), EVENT_ID, "Annual Summit", "I am no longer available.");
    }

    @Test
    @DisplayName("EC-C1 (negative): someone other than the assigned coordinator cannot ask for reassignment and nothing is saved")
    void someoneOtherThanTheAssignedCoordinatorCannotCreateARequest() {
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.request(UUID.randomUUID(), EVENT_ID, "Need a transfer"))
                .isInstanceOf(NotAssignedCoordinatorException.class);

        verify(requests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC-C1 (happy): the Lead's approval moves the event and its original request to the new coordinator and notifies everyone affected")
    void leadApprovalUpdatesEventAndOriginatingRequestAndNotifiesAffectedUsers() {
        EventReassignmentRequest request = pendingRequest();
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));
        when(users.findById(NEXT_COORDINATOR_ID)).thenReturn(Optional.of(nextCoordinator));

        var result = service.approve(LEAD_ID, REQUEST_ID, NEXT_COORDINATOR_ID);

        assertThat(event.getCoordinatorId()).isEqualTo(NEXT_COORDINATOR_ID);
        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        assertThat(origin.getCoordinatorId()).isEqualTo(NEXT_COORDINATOR_ID);
        assertThat(origin.getUpdatedAt().toInstant()).isEqualTo(NOW);
        assertThat(result.status()).isEqualTo(EventReassignmentStatus.approved);
        assertThat(result.coordinatorName()).isEqualTo("ec2");
        assertThat(request.getDecidedAt().toInstant()).isEqualTo(NOW);
        verify(activity).record(org.mockito.ArgumentMatchers.argThat(entry ->
                entry.type() == ActivityType.event_reassignment_approved));
        verify(notifications).createEventReassignmentDecisionNotifications(
                List.of(COORDINATOR_ID), EVENT_ID, "Annual Summit",
                NotificationType.event_reassignment_approved, "Reassigned from ec1 to ec2.");
        verify(notifications).createEventReassignmentDecisionNotifications(
                List.of(NEXT_COORDINATOR_ID), EVENT_ID, "Annual Summit",
                NotificationType.event_reassignment_approved, "Transferred to you from ec1. You are now coordinating this event.");
        verify(notifications).createCoordinatorAssignmentNotification(
                ORGANISER_ID, REQUEST_ID, EVENT_ID, "Annual Summit", "ec2", "ec2@example.test", true);
    }

    @Test
    @DisplayName("EC-C1 (negative): a completed event cannot be reassigned, the request stays pending and nothing changes")
    void completedEventsCannotBeReassignedAndTheRequestRemainsPending() {
        event.setStatus(EventStatus.completed);
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pendingRequest()));
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.approve(LEAD_ID, REQUEST_ID, NEXT_COORDINATOR_ID))
                .isInstanceOf(EventReassignmentStateException.class)
                .hasMessageContaining("Completed or cancelled");

        assertThat(event.getCoordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(eventRequests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC-C1 (conflict): a second request while one is pending is refused and nothing is saved")
    void aSecondPendingRequestIsRejectedWithoutCreatingAnotherRecord() {
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(requests.existsByEventIdAndStatus(EVENT_ID, EventReassignmentStatus.pending)).thenReturn(true);

        assertThatThrownBy(() -> service.request(COORDINATOR_ID, EVENT_ID, "Need a transfer."))
                .isInstanceOf(EventReassignmentStateException.class)
                .hasMessageContaining("already pending");

        verify(requests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC-C1 (negative): the Lead's decline leaves the event's coordinator unchanged and tells the requester why")
    void leadDeclineLeavesBothEventAssignmentsUntouchedAndNotifiesRequester() {
        EventReassignmentRequest request = pendingRequest();
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        var result = service.decline(LEAD_ID, REQUEST_ID, "Please continue with the current coordinator.");

        assertThat(result.status()).isEqualTo(EventReassignmentStatus.declined);
        assertThat(request.getDecisionReason()).isEqualTo("Please continue with the current coordinator.");
        assertThat(event.getCoordinatorId()).isEqualTo(COORDINATOR_ID);
        assertThat(origin.getCoordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(eventRequests, never()).save(any());
        verify(activity).record(org.mockito.ArgumentMatchers.argThat(entry ->
                entry.type() == ActivityType.event_reassignment_declined));
        verify(notifications).createEventReassignmentDecisionNotifications(
                List.of(COORDINATOR_ID), EVENT_ID, "Annual Summit",
                NotificationType.event_reassignment_declined,
                "Request declined: Please continue with the current coordinator.");
    }

    @Test
    @DisplayName("EC-C1 (conflict): when the original request no longer names the requester, approval is refused and nothing is saved")
    void approvalIsRefusedWhenTheOriginalRequestHasAnotherCoordinator() {
        origin.setCoordinatorId(NEXT_COORDINATOR_ID);
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pendingRequest()));
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(NEXT_COORDINATOR_ID)).thenReturn(Optional.of(nextCoordinator));

        assertThatThrownBy(() -> service.approve(LEAD_ID, REQUEST_ID, NEXT_COORDINATOR_ID))
                .isInstanceOf(EventReassignmentStateException.class)
                .hasMessage("The event request's assignment changed; refresh before approving this request.");

        assertThat(event.getCoordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(events, never()).save(any());
        verify(eventRequests, never()).save(any());
        verify(requests, never()).save(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC-C1 (boundary): a request with no recorded organiser is still reassigned, and only the two coordinators are told")
    void approvalWithoutAnOrganiserTellsOnlyTheCoordinators() {
        origin.setCreatedBy(null);
        when(requests.findForUpdate(REQUEST_ID)).thenReturn(Optional.of(pendingRequest()));
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));
        when(users.findById(NEXT_COORDINATOR_ID)).thenReturn(Optional.of(nextCoordinator));

        var result = service.approve(LEAD_ID, REQUEST_ID, NEXT_COORDINATOR_ID);

        assertThat(result.status()).isEqualTo(EventReassignmentStatus.approved);
        assertThat(event.getCoordinatorId()).isEqualTo(NEXT_COORDINATOR_ID);
        verify(notifications, org.mockito.Mockito.times(2)).createEventReassignmentDecisionNotifications(
                any(), any(), any(), any(), any());
        verify(notifications, never()).createCoordinatorAssignmentNotification(
                any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("EC-C1 (boundary): the Lead's queue shows a pending request on an event with no coordinator, with no coordinator named")
    void pendingRequestOnAnEventWithNoCoordinatorNamesNone() {
        event.setCoordinatorId(null);
        when(requests.findByStatusOrderByRequestedAtAsc(EventReassignmentStatus.pending))
                .thenReturn(List.of(pendingRequest()));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        var result = service.pendingRequests();

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.requesterName()).isEqualTo("ec1");
            assertThat(row.coordinatorUserId()).isNull();
            assertThat(row.coordinatorName()).isNull();
        });
    }

    private EventReassignmentRequest pendingRequest() {
        EventReassignmentRequest request = new EventReassignmentRequest();
        request.setReassignmentRequestId(REQUEST_ID);
        request.setEventId(EVENT_ID);
        request.setRequesterUserId(COORDINATOR_ID);
        request.setReason("I am no longer available.");
        request.setStatus(EventReassignmentStatus.pending);
        request.setRequestedAt(java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return request;
    }

    private Event event(EventStatus status) {
        Event value = new Event();
        value.setEventId(EVENT_ID);
        value.setEventName("Annual Summit");
        value.setOrganisation("Acme");
        value.setCoordinatorId(COORDINATOR_ID);
        value.setStatus(status);
        value.setStartDatetime(java.time.OffsetDateTime.parse("2027-03-10T01:00:00Z"));
        value.setEndDatetime(java.time.OffsetDateTime.parse("2027-03-10T04:00:00Z"));
        return value;
    }

    private User user(UUID id, UserRole role, String username) {
        User value = new User();
        value.setUserId(id);
        value.setRole(role);
        value.setUsername(username);
        value.setEmail(username + "@example.test");
        return value;
    }
}
