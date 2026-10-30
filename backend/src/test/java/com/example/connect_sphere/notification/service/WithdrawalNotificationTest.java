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
@Tag("AT08")
class WithdrawalNotificationTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService notifications = new NotificationService(repository);

    @Test
    @DisplayName("AT08 (happy): the withdrawal receipt carries its original reference and timestamp and opens the exact registration")
    void exactReceipt() {
        UUID owner = UUID.randomUUID(), event = UUID.randomUUID(), registration = UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        notifications.createAttendeeWithdrawalConfirmation(owner, event, "Town Hall", "CS-ORIGINAL", registration, when);
        var capture = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(capture.capture());
        Notification receipt = capture.getValue();
        assertThat(receipt.getType()).isEqualTo(NotificationType.attendee_registration_withdrawn);
        assertThat(receipt.getRegistrationId()).isEqualTo(registration);
        assertThat(receipt.getRecipientUserId()).isEqualTo(owner);
        assertThat(receipt.getOccurredAt()).isEqualTo(when);
        assertThat(receipt.getMessage()).isEqualTo("Your registration has been withdrawn. Reference: CS-ORIGINAL");
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(owner)).thenReturn(List.of(receipt));
        assertThat(notifications.list(owner).getFirst().linkPath()).isEqualTo("/attendee/my-registrations/" + registration);
    }

    @Test
    @DisplayName("AT08 (conflict): duplicate withdrawal enqueue uses the same identity and a different registration gets its own receipt")
    void stableIdentity() {
        UUID owner = UUID.randomUUID(), event = UUID.randomUUID(), registration = UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        notifications.createAttendeeWithdrawalConfirmation(owner, event, "Event", "CS-ONE", registration, when);
        notifications.createAttendeeWithdrawalConfirmation(owner, event, "Renamed", "CS-ONE", registration, when);
        notifications.createAttendeeWithdrawalConfirmation(owner, event, "Event", "CS-TWO", UUID.randomUUID(), when);
        var capture = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(3)).save(capture.capture());
        var receipts = capture.getAllValues();
        assertThat(receipts.get(0).getNotificationId()).isEqualTo(receipts.get(1).getNotificationId());
        assertThat(receipts.get(0).getNotificationId()).isNotEqualTo(receipts.get(2).getNotificationId());
    }
}
