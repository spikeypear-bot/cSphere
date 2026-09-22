package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    private NotificationService service;

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void eo09NotificationCarriesEventReferenceStatusAndTimestamp() {
        UUID requestId = UUID.randomUUID();
        service.createStatusChangeNotification(RECIPIENT, requestId, null, "Town Hall", "approved", null);

        Notification saved = captureSaved();
        assertThat(saved.getRecipientUserId()).isEqualTo(RECIPIENT);
        assertThat(saved.getType()).isEqualTo(NotificationType.status_change);
        assertThat(saved.getEventName()).isEqualTo("Town Hall");
        assertThat(saved.getNewStatus()).isEqualTo("approved");
        assertThat(saved.getOccurredAt()).isNotNull();
        assertThat(saved.getReadAt()).isNull(); // unread by default
    }

    @Test
    void eo09RejectionNotificationCarriesTheReason() {
        service.createStatusChangeNotification(
                RECIPIENT, UUID.randomUUID(), null, "Town Hall", "rejected", "No venue available");

        assertThat(captureSaved().getReason()).isEqualTo("No venue available");
    }

    @Test
    void eo19NotificationDistinguishesInitialFromReassignment() {
        service.createCoordinatorAssignmentNotification(
                RECIPIENT, UUID.randomUUID(), null, "Town Hall", "ec1", "ec1@connectsphere.test", false);
        assertThat(captureSaved().getIsReassignment()).isFalse();

        org.mockito.Mockito.clearInvocations(repository);

        service.createCoordinatorAssignmentNotification(
                RECIPIENT, UUID.randomUUID(), null, "Town Hall", "ec2", "ec2@connectsphere.test", true);
        assertThat(captureSaved().getIsReassignment()).isTrue();
    }

    @Test
    void aNotificationSaveFailureIsSwallowedNotThrown() {
        when(repository.save(any())).thenThrow(new RuntimeException("db blip"));

        // Must not throw — the whole point is that a caller mid-transaction
        // (EventRequestService.approve(), etc.) is unaffected.
        service.createStatusChangeNotification(RECIPIENT, UUID.randomUUID(), null, "Town Hall", "approved", null);
    }

    @Test
    void listReturnsNewestFirstAndMapsReadState() {
        Notification unread = notification(RECIPIENT, null);
        Notification read = notification(RECIPIENT, OffsetDateTime.now());
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(RECIPIENT))
                .thenReturn(List.of(unread, read));

        List<NotificationDto> result = service.list(RECIPIENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).read()).isFalse();
        assertThat(result.get(1).read()).isTrue();
    }

    @Test
    void linkPathPointsAtTheEventWhenOneExistsOtherwiseTheRequest() {
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(RECIPIENT)).thenReturn(List.of(
                notificationFor(requestId, null),
                notificationFor(requestId, eventId)));

        List<NotificationDto> result = service.list(RECIPIENT);

        assertThat(result.get(0).linkPath()).isEqualTo("/organiser/requests/" + requestId);
        assertThat(result.get(1).linkPath()).isEqualTo("/organiser/events/" + eventId);
    }

    @Test
    void markingAsReadIsIdempotent() {
        Notification unread = notification(RECIPIENT, null);
        when(repository.findById(unread.getNotificationId())).thenReturn(Optional.of(unread));

        NotificationDto first = service.markRead(RECIPIENT, unread.getNotificationId());
        assertThat(first.read()).isTrue();

        // Marking again must not error and must not write a second time —
        // EO09/EO19's "allows marking as read without changing anything else"
        // implies re-marking is a no-op, not a fresh write.
        NotificationDto second = service.markRead(RECIPIENT, unread.getNotificationId());
        assertThat(second.read()).isTrue();
        verify(repository, times(1)).save(any());
    }

    @Test
    void cannotMarkSomeoneElsesNotificationAsRead() {
        Notification othersNotification = notification(SOMEONE_ELSE, null);
        when(repository.findById(othersNotification.getNotificationId()))
                .thenReturn(Optional.of(othersNotification));

        assertThatThrownBy(() -> service.markRead(RECIPIENT, othersNotification.getNotificationId()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void markingANonexistentNotificationAsReadIsReportedAsNotFound() {
        UUID ghost = UUID.randomUUID();
        when(repository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(RECIPIENT, ghost))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void unreadCountDelegatesToTheRepositoryCount() {
        when(repository.countByRecipientUserIdAndReadAtIsNull(RECIPIENT)).thenReturn(3L);
        assertThat(service.unreadCount(RECIPIENT)).isEqualTo(3L);
    }

    private Notification captureSaved() {
        org.mockito.ArgumentCaptor<Notification> captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private static Notification notification(UUID recipient, OffsetDateTime readAt) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setRecipientUserId(recipient);
        n.setType(NotificationType.status_change);
        n.setEventName("Town Hall");
        n.setNewStatus("approved");
        n.setOccurredAt(OffsetDateTime.now());
        n.setReadAt(readAt);
        return n;
    }

    private static Notification notificationFor(UUID requestId, UUID eventId) {
        Notification n = notification(RECIPIENT, null);
        n.setEventRequestId(requestId);
        n.setEventId(eventId);
        return n;
    }
}
