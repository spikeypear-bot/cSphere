package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

/**
 * EO07's notifications: each reaches the right person, carries the reason,
 * and links into a page that person can open (or nowhere, for staff with no
 * event page).
 */
@Tag("unit")
@Tag("EO07")
class EventCancellationNotificationTest {

    @Mock private NotificationRepository repository;
    private NotificationService service;

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<NotificationDto> delivered() {
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(any())).thenReturn(saved.getAllValues());
        return service.list(RECIPIENT);
    }

    @Test
    @DisplayName("EO07 AC2 (happy): the coordinator is told a request they were reviewing was cancelled, with the reason, linking to that request")
    void aCancelledRequestReachesItsCoordinator() {
        service.createRequestCancelledNotification(RECIPIENT, REQUEST, "Town Hall", "Called off");

        NotificationDto sent = delivered().get(0);
        assertThat(sent.type()).isEqualTo(NotificationType.request_cancelled.name());
        assertThat(sent.eventRequestId()).isEqualTo(REQUEST);
        assertThat(sent.message()).isEqualTo("Called off");
        assertThat(sent.linkPath()).isEqualTo("/coordinator/requests/" + REQUEST);
    }

    @Test
    @DisplayName("EO07 AC7 (happy): the assigned coordinator is told of a cancellation request, with the reason, linking to the event they decide it on")
    void aCancellationRequestReachesTheCoordinatorAndOpensTheEvent() {
        service.createEventCancellationRequestedNotification(RECIPIENT, EVENT, "Town Hall", "Called off");

        NotificationDto sent = delivered().get(0);
        assertThat(sent.type()).isEqualTo(NotificationType.event_cancellation_requested.name());
        assertThat(sent.eventId()).isEqualTo(EVENT);
        assertThat(sent.message()).isEqualTo("Called off");
        assertThat(sent.linkPath()).isEqualTo("/coordinator/events/" + EVENT);
    }

    @Test
    @DisplayName("EO07 AC10 (happy): the organiser is told the outcome, with the coordinator's reason when declined, linking to their event")
    void theOutcomeReachesTheOrganiserAndOpensTheirEvent() {
        service.createEventCancellationDecisionNotification(RECIPIENT, EVENT, "Town Hall",
                NotificationType.event_cancellation_declined, "Cancellation declined: Deposit is non-refundable");
        service.createEventCancellationDecisionNotification(RECIPIENT, EVENT, "Town Hall",
                NotificationType.event_cancellation_approved, "Town Hall has been cancelled as you asked.");

        List<NotificationDto> sent = delivered();
        assertThat(sent).extracting(NotificationDto::type).containsExactly(
                "event_cancellation_declined", "event_cancellation_approved");
        assertThat(sent.get(0).message()).isEqualTo("Cancellation declined: Deposit is non-refundable");
        assertThat(sent).allSatisfy(n -> assertThat(n.linkPath()).isEqualTo("/organiser/events/" + EVENT));
    }

    @Test
    @DisplayName("EO07 AC10 (happy): each Venue Staff and Technical Support recipient is told once that the event is cancelled; they have no event page, so it links nowhere")
    void staffAreToldTheEventIsCancelled() {
        UUID other = UUID.randomUUID();
        service.createEventCancelledNotifications(List.of(RECIPIENT, other), EVENT, "Town Hall", "Town Hall is cancelled.");

        List<NotificationDto> sent = delivered();
        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.event_cancelled.name());
            assertThat(n.eventId()).isEqualTo(EVENT);
            assertThat(n.message()).isEqualTo("Town Hall is cancelled.");
            assertThat(n.linkPath()).isNull();
        });
    }
}
