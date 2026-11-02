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
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationDelivery;
import com.example.connect_sphere.notification.service.NotificationService;

@SpringBootTest @Tag("integration") @Tag("EC08") @Tag("SO-C1")
class SafetyReviewNoticeDeliveryTest {
    @Autowired NotificationService notifications;
    @Autowired NotificationDelivery delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final UUID event = UUID.randomUUID();

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM notifications WHERE event_id=?", event);
        jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id'=?", event.toString());
        jdbc.update("DELETE FROM events WHERE event_id=?", event);
    }

    @Test @DisplayName("EC08 SO-C1 (happy): SO notice commits, delivers once across retries, and links to the safety review")
    void durableDeliveryAndCorrectLink() {
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status)
            VALUES (?,'Safety delivery','Test','2030-04-01 09:00+08','2030-04-01 12:00+08',50,'Room','awaiting_safety_review')
            """, event);
        UUID officer = jdbc.queryForObject("SELECT user_id FROM users WHERE username='so1'", UUID.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                notifications.createSafetyReviewRequestedNotifications(List.of(officer, officer), event, "Safety delivery"));
        var notices = notifications.list(officer).stream().filter(n -> event.equals(n.eventId())).toList();
        assertThat(notices).hasSize(1);
        assertThat(notices.getFirst().type()).isEqualTo(NotificationType.safety_review_requested.name());
        assertThat(notices.getFirst().linkPath()).isEqualTo("/safety-officer/events/" + event);
        delivery.deliver(notices.getFirst().notificationId());
        delivery.deliver(notices.getFirst().notificationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=? AND delivered_at IS NOT NULL",
                Integer.class, event.toString())).isEqualTo(1);
    }

    @Test @DisplayName("EC08 SO-C1 (happy): withdrawal delivers once with its reason and links to the current queue")
    void withdrawalNoticeIsDurable() {
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status)
            VALUES (?,'Withdrawn workshop','Test','2030-04-01 09:00+08','2030-04-01 12:00+08',50,'Room','pending')
            """, event);
        UUID officer = jdbc.queryForObject("SELECT user_id FROM users WHERE username='so1'", UUID.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                notifications.createSafetyReviewWithdrawnNotifications(List.of(officer, officer), event, "Withdrawn workshop", "Attendance changed"));
        var notices = notifications.list(officer).stream().filter(n -> event.equals(n.eventId())).toList();
        assertThat(notices).hasSize(1);
        assertThat(notices.getFirst().type()).isEqualTo("safety_review_withdrawn");
        assertThat(notices.getFirst().reason()).isEqualTo("Attendance changed");
        assertThat(notices.getFirst().linkPath()).isEqualTo("/safety-officer/reviews");
        delivery.deliver(notices.getFirst().notificationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, event)).isEqualTo(1);
    }

    @Test @DisplayName("EC08 SO-C1 (failure): a rolled-back withdrawal creates neither a notice nor pending delivery")
    void rollbackLeavesNoWithdrawalNotice() {
        UUID officer = jdbc.queryForObject("SELECT user_id FROM users WHERE username='so1'", UUID.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            notifications.createSafetyReviewWithdrawnNotifications(List.of(officer), event, "Withdrawn workshop", "Attendance changed");
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?", Integer.class, event.toString())).isZero();
    }
}
