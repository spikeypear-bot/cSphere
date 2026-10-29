package com.example.connect_sphere.notification.service;

import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.connect_sphere.notification.entity.Notification;
import tools.jackson.databind.ObjectMapper;

/** In-app delivery may fail independently; the work item must commit with its triggering action. */
@Service
public class NotificationOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NotificationDelivery delivery;
    public NotificationOutbox(JdbcTemplate jdbc, ObjectMapper json, NotificationDelivery delivery) {
        this.jdbc=jdbc; this.json=json; this.delivery=delivery;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(Notification n) {
        var payload=new LinkedHashMap<String,Object>();
        payload.put("notification_id",n.getNotificationId()); payload.put("recipient_user_id",n.getRecipientUserId());
        payload.put("event_request_id",n.getEventRequestId()); payload.put("event_id",n.getEventId());
        payload.put("registration_id",n.getRegistrationId());
        payload.put("type",n.getType().name()); payload.put("event_name",n.getEventName());
        payload.put("new_status",n.getNewStatus()); payload.put("reason",n.getReason());
        payload.put("coordinator_name",n.getCoordinatorName()); payload.put("coordinator_email",n.getCoordinatorEmail());
        payload.put("is_reassignment",n.getIsReassignment()); payload.put("occurred_at",n.getOccurredAt().toString());
        payload.put("read_at",null); payload.put("message",n.getMessage());
        payload.put("venue_booking_id",n.getVenueBookingId()); payload.put("requirement_review_id",n.getRequirementReviewId());
        jdbc.update("INSERT INTO notification_outbox(notification_id,payload) VALUES (?,?::jsonb) ON CONFLICT (notification_id) DO NOTHING",
            n.getNotificationId(),json.writeValueAsString(payload));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { attempt(n.getNotificationId()); }
        });
    }
    private void attempt(UUID id) {
        try { delivery.deliver(id); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Notification {} remains queued for retry",id,ex); }
    }
    @Scheduled(fixedDelayString="${notifications.retry-delay-ms:30000}",initialDelayString="${notifications.retry-delay-ms:30000}")
    public void retry() {
        jdbc.query("SELECT notification_id FROM notification_outbox WHERE delivered_at IS NULL ORDER BY created_at,notification_id LIMIT 100",
            (r,n) -> r.getObject(1,UUID.class)).forEach(this::attempt);
    }
}
