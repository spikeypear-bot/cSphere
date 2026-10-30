package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Tag("AT08")
class AttendeeWithdrawalMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("AT08 (cross-cutting): V56 preserves populated V55 registrations and leaves historical withdrawal times unknown")
    void upgradesPopulatedV55() throws Exception {
        String schema = "at08_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("55").load().migrate();
            try (Connection connection = dataSource.getConnection()) {
                String previous = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    UUID eventId = UUID.randomUUID();
                    db.update("""
                            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,
                                expected_attendance,venue_requirements,status)
                            VALUES (?, 'Migration history', 'Test', now()+interval '2 days', now()+interval '3 days',
                                2, 'None', 'confirmed')
                            """, eventId);
                    for (String state : new String[] {"confirmed", "withdrawn", "event_cancelled"}) {
                        db.update("""
                                INSERT INTO event_registrations(registration_id,event_id,attendee_name,reference,registered_at,status)
                                VALUES (?,?, 'Historical attendee', ?, '2026-01-01T00:00:00Z', ?)
                                """, UUID.randomUUID(), eventId, "OLD-" + state, state);
                    }
                    var before = db.queryForList("SELECT * FROM event_registrations ORDER BY reference");
                    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
                    assertThat(db.queryForList("SELECT registration_id,event_id,attendee_name,reference,registered_at,status,attendee_id,attendee_email FROM event_registrations ORDER BY reference"))
                            .isEqualTo(before);
                    assertThat(db.queryForObject("SELECT count(*) FROM event_registrations WHERE withdrawn_at IS NULL", Integer.class))
                            .isEqualTo(3);
                    assertThat(db.queryForObject("""
                            SELECT data_type FROM information_schema.columns
                            WHERE table_schema=? AND table_name='event_registrations' AND column_name='withdrawn_at'
                            """, String.class, schema)).isEqualTo("timestamp with time zone");
                    UUID owner = UUID.randomUUID();
                    db.update("INSERT INTO users(user_id,hashed_password,email,username,role) VALUES (?, 'unused', 'migration@example.com', 'at08-migration', 'attendee')", owner);
                    UUID registration = db.queryForObject("SELECT registration_id FROM event_registrations WHERE status='withdrawn'", UUID.class);
                    db.update("""
                            INSERT INTO notifications(notification_id,recipient_user_id,event_id,registration_id,type,event_name,message)
                            VALUES (?,?,?,?, 'attendee_registration_withdrawn','Migration event','Withdrawn')
                            """, UUID.randomUUID(), owner, eventId, registration);
                    for (String missing : new String[] {"event", "registration", "message"}) {
                        assertThatThrownBy(() -> db.update("""
                                INSERT INTO notifications(notification_id,recipient_user_id,event_id,registration_id,type,event_name,message)
                                VALUES (?,?,?,?, 'attendee_registration_withdrawn','Migration event',?)
                                """, UUID.randomUUID(), owner, missing.equals("event") ? null : eventId,
                                missing.equals("registration") ? null : registration, missing.equals("message") ? null : "Withdrawn"))
                                .hasMessageContaining("chk_notification_shape");
                    }
                    db.update("INSERT INTO notifications(notification_id,recipient_user_id,type,event_name,new_status) VALUES (?,?,'status_change','Existing shape','approved')", UUID.randomUUID(), owner);
                    assertThat(db.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isEqualTo(2);
                } finally {
                    connection.setSchema(previous);
                }
            }
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
