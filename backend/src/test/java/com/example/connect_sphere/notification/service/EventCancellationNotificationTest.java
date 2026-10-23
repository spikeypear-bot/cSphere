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
}
