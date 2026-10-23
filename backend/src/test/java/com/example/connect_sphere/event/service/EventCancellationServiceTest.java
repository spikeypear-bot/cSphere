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
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.InvalidMessageException;
import com.example.connect_sphere.notification.service.NotificationService;

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
    @Mock private EventRequestRepository eventRequests;
    @Mock private EventRequestMapper requestMapper;
    @Mock private ActivityService activity;
    @Mock private NotificationService notifications;

    private EventCancellationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EventCancellationService(eventRequests, requestMapper, activity, notifications,
                new JourneyStatusResolver(events, clock), clock);
        when(eventRequests.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
}
