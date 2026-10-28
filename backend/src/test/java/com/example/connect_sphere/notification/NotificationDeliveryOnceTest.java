package com.example.connect_sphere.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
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

import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationDelivery;
import com.example.connect_sphere.notification.service.NotificationOutbox;

/**
 * EC-NEW4 AC28, AC30: a notice is delivered once however often delivery runs,
 * including a retry after delivery inserted it but failed before recording
 * that. This needs real commits (delivery runs in its own transaction after
 * the action commits), so the rows are removed afterwards.
 */
@Tag("integration")
@Tag("EC-NEW4")
@SpringBootTest
class NotificationDeliveryOnceTest {

    @Autowired NotificationOutbox outbox;
    @Autowired NotificationDelivery delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notifications WHERE notification_id = ?", id);
        jdbc.update("DELETE FROM notification_outbox WHERE notification_id = ?", id);
    }

    private long delivered() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE notification_id = ?", Long.class, id);
    }

    @Test
    @DisplayName("EC-NEW4 AC28, AC30 (conflict): delivery after commit, a repeat, and a retry after a lost acknowledgement leave exactly one notice, unchanged")
    void deliveredExactlyOnce() {
        Notification n = new Notification();
        n.setNotificationId(id);
        n.setRecipientUserId(jdbc.queryForObject("SELECT user_id FROM users WHERE username = 'ec1'", UUID.class));
        n.setType(NotificationType.status_change);
        n.setNewStatus("approved");
        n.setEventName("AC28 Once " + id);
        n.setMessage("Approved");
        n.setActorName("ecl1");
        n.setActorRole("ecl");
        n.setOccurredAt(OffsetDateTime.now());

        // Delivered by the after-commit hook once the action commits.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> outbox.enqueue(n));
        assertThat(delivered()).isEqualTo(1);

        // Delivering again (a second worker, or the scheduled retry) adds nothing.
        delivery.deliver(id);
        // The notice was stored but the outbox was not marked: the retry must not duplicate it.
        jdbc.update("UPDATE notification_outbox SET delivered_at = NULL WHERE notification_id = ?", id);
        delivery.deliver(id);

        assertThat(delivered()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_name, actor_role, read_at FROM notifications WHERE notification_id = ?", id);
        assertThat(row).containsEntry("actor_name", "ecl1").containsEntry("actor_role", "ecl");
        assertThat(row.get("read_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT delivered_at IS NOT NULL FROM notification_outbox WHERE notification_id = ?",
                Boolean.class, id)).isTrue();
    }
}
