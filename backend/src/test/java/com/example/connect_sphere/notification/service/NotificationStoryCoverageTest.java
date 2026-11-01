package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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

import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

@Tag("unit")
@Tag("EC-C1")
@Tag("TS09")
class NotificationStoryCoverageTest {

    @Mock
    private NotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(repository);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("EC-C1 (happy): reassignment requests and decisions notify their recipients with the event and outcome")
    void reassignmentNotificationsReachAffectedUsers() {
        UUID eventId = UUID.randomUUID();
        UUID leadOne = UUID.randomUUID();
        UUID leadTwo = UUID.randomUUID();
        UUID requestor = UUID.randomUUID();
        UUID newCoordinator = UUID.randomUUID();
        UUID organiser = UUID.randomUUID();

        service.createEventReassignmentRequestedNotifications(
                List.of(leadOne, leadTwo), eventId, "Town Hall", "Workload conflict");
        service.createEventReassignmentDecisionNotifications(
                List.of(requestor, newCoordinator, organiser), eventId, "Town Hall",
                NotificationType.event_reassignment_approved, "Reassigned to ec2.");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(5)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();

        assertThat(saved).extracting(Notification::getRecipientUserId)
                .containsExactly(leadOne, leadTwo, requestor, newCoordinator, organiser);
        assertThat(saved).extracting(Notification::getType).containsExactly(
                NotificationType.event_reassignment_requested,
                NotificationType.event_reassignment_requested,
                NotificationType.event_reassignment_approved,
                NotificationType.event_reassignment_approved,
                NotificationType.event_reassignment_approved);
        assertThat(saved).extracting(Notification::getEventId).containsOnly(eventId);
        assertThat(saved).extracting(Notification::getMessage).containsExactly(
                "Workload conflict", "Workload conflict", "Reassigned to ec2.",
                "Reassigned to ec2.", "Reassigned to ec2.");

        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(leadOne)).thenReturn(List.of(saved.get(0)));
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(newCoordinator)).thenReturn(List.of(saved.get(3)));
        assertThat(service.list(leadOne).get(0).linkPath())
                .isEqualTo("/coordinator-lead/reassignment-requests");
        assertThat(service.list(newCoordinator).get(0).linkPath())
                .isEqualTo("/coordinator/events/" + eventId + "/reassignment-request");
    }

    @Test
    @DisplayName("TS09 AC16 (happy): the assigned coordinator receives the damage outcome linked to its event")
    void equipmentDamageOutcomeNotificationLinksToEvent() {
        UUID coordinatorId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        service.createEquipmentDamageOutcomeNotification(
                coordinatorId, eventId, "Town Hall", "Equipment is covered by spare stock.");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getRecipientUserId()).isEqualTo(coordinatorId);
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getEventName()).isEqualTo("Town Hall");
        assertThat(saved.getType()).isEqualTo(NotificationType.equipment_damage_outcome);
        assertThat(saved.getMessage()).isEqualTo("Equipment is covered by spare stock.");

        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(coordinatorId)).thenReturn(List.of(saved));
        assertThat(service.list(coordinatorId).get(0).linkPath())
                .isEqualTo("/coordinator/events/" + eventId);
    }
}
