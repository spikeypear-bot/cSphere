package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@SpringBootTest
@Tag("integration")
@Tag("AT03")
class RegistrationConfirmationMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("AT03 (cross-cutting): upgrading main's V54 links old and queued notices to the exact owned registration, including withdrawn history")
    void upgradesHistoricalAndQueuedConfirmations() throws Exception {
        String schema = "at03_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("54").load().migrate();
            try (Connection connection = dataSource.getConnection()) {
                String original = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), event = UUID.randomUUID();
                    db.update("INSERT INTO users(user_id,hashed_password,email,username,role) VALUES (?,'unused','at03@test.example','at03-owner','attendee'),(?,'unused','other@test.example','at03-other','attendee')", owner, other);
                    db.update("INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status) VALUES (?,'Migration event','AT03',now()+interval '2 days',now()+interval '3 days',20,'None','confirmed')", event);
                    UUID old = UUID.randomUUID(), active = UUID.randomUUID();
                    db.update("INSERT INTO event_registrations(registration_id,event_id,attendee_id,attendee_name,reference,registered_at,status) VALUES (?,?,?,'attendee','CS-OLD',now(),'withdrawn'),(?,?,?,'attendee','CS-NEW',now(),'confirmed')", old, event, owner, active, event, owner);
                    UUID oldNotice = UUID.randomUUID(), newNotice = UUID.randomUUID(), unsafe = UUID.randomUUID(), queued = UUID.randomUUID();
                    for (Object[] notice : new Object[][] {{oldNotice, owner, "CS-OLD"}, {newNotice, owner, "CS-NEW"}, {unsafe, other, "CS-OLD"}}) {
                        db.update("INSERT INTO notifications(notification_id,recipient_user_id,event_id,type,event_name,message) VALUES (?,?,?,'attendee_registration_confirmed','Migration event',?)", notice[0], notice[1], event, "Your registration is confirmed. Reference: " + notice[2]);
                    }
                    db.update("INSERT INTO notification_outbox(notification_id,payload) VALUES (?,jsonb_build_object('notification_id',?::text,'recipient_user_id',?::text,'event_id',?::text,'type','attendee_registration_confirmed','message','Your registration is confirmed. Reference: CS-OLD'))", queued, queued, owner, event);
                    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
                    assertThat(db.queryForObject("SELECT registration_id FROM notifications WHERE notification_id=?", UUID.class, oldNotice)).isEqualTo(old);
                    assertThat(db.queryForObject("SELECT registration_id FROM notifications WHERE notification_id=?", UUID.class, newNotice)).isEqualTo(active);
                    assertThat(db.queryForObject("SELECT registration_id FROM notifications WHERE notification_id=?", UUID.class, unsafe)).isNull();
                    assertThat(db.queryForObject("SELECT payload->>'registration_id' FROM notification_outbox WHERE notification_id=?", String.class, queued)).isEqualTo(old.toString());
                    assertThat(db.queryForList("SELECT reference FROM event_registrations ORDER BY reference", String.class)).containsExactly("CS-NEW", "CS-OLD");
                    assertThat(db.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isEqualTo(3);
                } finally { connection.setSchema(original); }
            }
        } finally { admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
}
