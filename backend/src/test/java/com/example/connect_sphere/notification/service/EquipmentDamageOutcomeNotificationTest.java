package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
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
@Tag("TS09")
class EquipmentDamageOutcomeNotificationTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService notifications = new NotificationService(repository, Clock.systemUTC());

    @Test
    @DisplayName("TS09 AC16 (happy): the coordinator is told the damage outcome for their event, and the notice opens that event")
    void theCoordinatorIsToldTheOutcome() {
        UUID coordinator = UUID.randomUUID(), event = UUID.randomUUID();

        notifications.createEquipmentDamageOutcomeNotification(coordinator, event, "Town Hall",
                "2 projectors are short for this event.");

        var capture = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(capture.capture());
        Notification notice = capture.getValue();
        assertThat(notice.getRecipientUserId()).isEqualTo(coordinator);
        assertThat(notice.getType()).isEqualTo(NotificationType.equipment_damage_outcome);
        assertThat(notice.getEventId()).isEqualTo(event);
        assertThat(notice.getEventName()).isEqualTo("Town Hall");
        assertThat(notice.getMessage()).isEqualTo("2 projectors are short for this event.");
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(coordinator)).thenReturn(List.of(notice));
        assertThat(notifications.list(coordinator).getFirst().linkPath()).isEqualTo("/coordinator/events/" + event);
    }
}
