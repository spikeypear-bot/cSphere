package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

@Tag("unit")
@Tag("AT03")
class RegistrationConfirmationNotificationTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(repository);

    @Test
    @DisplayName("AT03 (happy): registration identity controls the confirmation link independently of event snapshots")
    void exactRegistrationLink() {
        UUID owner = UUID.randomUUID(), registration = UUID.randomUUID();
        Notification notification = notice(owner);
        notification.setRegistrationId(registration);
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(owner)).thenReturn(List.of(notification));
        assertThat(service.list(owner).getFirst().linkPath()).isEqualTo("/attendee/my-registrations/" + registration);
    }

    @Test
    @DisplayName("AT03 (boundary): an unmatched historical confirmation retains its existing event link")
    void legacyLink() {
        UUID owner = UUID.randomUUID();
        Notification notification = notice(owner);
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(owner)).thenReturn(List.of(notification));
        assertThat(service.list(owner).getFirst().linkPath()).isEqualTo("/attendee/events/" + notification.getEventId());
    }

    @Test
    @DisplayName("AT03 (conflict): one registration gets a stable identity, while a later re-registration gets a distinct confirmation")
    void stableIdentity() {
        UUID owner = UUID.randomUUID(), event = UUID.randomUUID(), original = UUID.randomUUID();
        service.createAttendeeRegistrationConfirmation(owner, event, "Event", "CS-ONE", original);
        service.createAttendeeRegistrationConfirmation(owner, event, "Renamed event", "CS-ONE", original);
        service.createAttendeeRegistrationConfirmation(owner, event, "Event", "CS-TWO", UUID.randomUUID());
        var capture = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(3)).save(capture.capture());
        var sent = capture.getAllValues();
        assertThat(sent.get(0).getNotificationId()).isEqualTo(sent.get(1).getNotificationId());
        assertThat(sent.get(0).getNotificationId()).isNotEqualTo(sent.get(2).getNotificationId());
        assertThat(sent.get(0).getRegistrationId()).isEqualTo(original);
    }

    private Notification notice(UUID owner) {
        Notification notification = new Notification();
        notification.setNotificationId(UUID.randomUUID()); notification.setRecipientUserId(owner);
        notification.setType(NotificationType.attendee_registration_confirmed);
        notification.setEventId(UUID.randomUUID()); notification.setOccurredAt(OffsetDateTime.now());
        return notification;
    }
}
