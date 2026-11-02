package com.example.connect_sphere.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.notification.service.NotificationService;

/**
 * EC-NEW3 (D37): the coordinator's "equipment request needs attention" notice
 * must reach their notifications, not just the outbox. Delivery runs after the
 * action commits, so this needs real commits; its rows are removed afterwards.
 */
@Tag("integration")
@Tag("EC-NEW3")
@SpringBootTest
class EquipmentAttentionNoticeDeliveryTest {

    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final UUID eventId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notifications WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id' = ?", eventId.toString());
        jdbc.update("DELETE FROM events WHERE event_id = ?", eventId);
    }

    @Test
    @DisplayName("EC-NEW3 (D37) (happy): the coordinator's equipment-attention notice is delivered, not left queued in the outbox")
    void attentionNoticeIsDelivered() {
        jdbc.update("""
                INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status)
                VALUES (?, 'Attention notice gala', 'Test', '2030-04-01 09:00+08', '2030-04-01 12:00+08', 50, 'Room', 'pending')
                """, eventId);
        UUID coordinator = jdbc.queryForObject("SELECT user_id FROM users WHERE username = 'ec1'", UUID.class);

        new TransactionTemplate(transactions).executeWithoutResult(tx -> notifications.createEquipmentRequestAttentionNotification(
                coordinator, eventId, "Attention notice gala", "The event's equipment requirements text changed."));

        List<String> delivered = jdbc.queryForList("""
                SELECT message FROM notifications
                WHERE event_id = ? AND type = 'equipment_request_attention' AND recipient_user_id = ?
                """, String.class, eventId, coordinator);
        assertThat(delivered).containsExactly("The equipment request needs attention: The event's equipment requirements text changed.");
    }
}
