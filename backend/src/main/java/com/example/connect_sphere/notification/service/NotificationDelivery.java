package com.example.connect_sphere.notification.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDelivery {
    private final JdbcTemplate jdbc;
    public NotificationDelivery(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void deliver(UUID id) {
        var pending=jdbc.query("SELECT notification_id FROM notification_outbox WHERE notification_id=? AND delivered_at IS NULL FOR UPDATE SKIP LOCKED",
            (r,n) -> r.getObject(1,UUID.class),id);
        if (pending.isEmpty()) return;
        jdbc.update("""
            INSERT INTO notifications SELECT (jsonb_populate_record(NULL::notifications,payload)).*
            FROM notification_outbox WHERE notification_id=? ON CONFLICT (notification_id) DO NOTHING
            """,id);
        jdbc.update("UPDATE notification_outbox SET delivered_at=clock_timestamp() WHERE notification_id=?",id);
    }
}
