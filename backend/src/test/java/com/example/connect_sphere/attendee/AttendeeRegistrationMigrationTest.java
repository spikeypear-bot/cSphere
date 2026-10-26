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
@Tag("AT01")
@Tag("AT02")
@Tag("EO10")
@Tag("TS09")
class AttendeeRegistrationMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("AT01 AT02 EO10 TS09 (cross-cutting): upgrading populated V40 preserves registrations and notification constraints")
    void upgradesPopulatedV40() throws Exception {
        String schema = "pr82_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("40").load().migrate();
            try (Connection connection = dataSource.getConnection()) {
                String previousSchema = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    UUID event = UUID.randomUUID();
                    UUID attendee = UUID.randomUUID();
                    db.update("INSERT INTO users(user_id, hashed_password, email, username, role) VALUES (?, 'unused', 'test@example.com', 'migration-attendee', 'attendee')", attendee);
                    db.update("""
                            INSERT INTO events(event_id, event_name, purpose, start_datetime, end_datetime,
                                expected_attendance, venue_requirements, status, registration_needs,
                                registration_capacity, registration_opens_at)
                            VALUES (?, 'Migration event', 'Test', now() + interval '2 days', now() + interval '3 days',
                                10, 'None', 'confirmed', true, 10, now() - interval '1 day')
                            """, event);
                    for (String status : new String[] {"confirmed", "withdrawn", "event_cancelled"}) {
                        db.update("INSERT INTO event_registrations VALUES (?, ?, 'Legacy attendee', ?, now(), ?)",
                                UUID.randomUUID(), event, "OLD-" + status, status);
                    }
                    UUID venue = UUID.randomUUID();
                    db.update("INSERT INTO venues(venue_id, venue_address, venue_capacity, operating_information, supported_layouts) VALUES (?, 'Legacy address', 10, 'Daily', ARRAY['theatre'])", venue);
                    insertNotification(db, event, attendee, "equipment_damage_outcome", "Existing damage report");

                    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

                    assertThat(db.queryForList("SELECT reference FROM event_registrations ORDER BY reference", String.class))
                            .containsExactly("OLD-confirmed", "OLD-event_cancelled", "OLD-withdrawn");
                    assertThat(db.queryForObject("SELECT count(*) FROM event_registrations WHERE attendee_id IS NULL AND attendee_email IS NULL", Integer.class)).isEqualTo(3);
                    assertThat(db.queryForObject("SELECT venue_name FROM venues WHERE venue_id = ?", String.class, venue)).isEqualTo("Legacy address");
                    assertThat(db.queryForObject("SELECT registration_capacity FROM events WHERE event_id = ?", Integer.class, event)).isEqualTo(10);
                    assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name IN ('events', 'event_requests') AND column_name = 'registration_closes_at'", Integer.class, schema)).isZero();
                    assertThat(db.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isEqualTo(1);

                    for (String type : new String[] {"equipment_damage_outcome", "attendee_registration_confirmed"}) {
                        insertNotification(db, event, attendee, type, "Valid message");
                        assertThatThrownBy(() -> insertNotification(db, event, attendee, type, null))
                                .hasMessageContaining("chk_notification_shape");
                        assertThatThrownBy(() -> insertNotification(db, null, attendee, type, "Missing event"))
                                .hasMessageContaining("chk_notification_shape");
                    }
                    db.update("INSERT INTO event_registrations(registration_id, event_id, attendee_name, reference, registered_at, status, attendee_id, attendee_email) VALUES (?, ?, 'New attendee', 'NEW-1', now(), 'confirmed', ?, 'test@example.com')",
                            UUID.randomUUID(), event, attendee);
                    assertThatThrownBy(() -> db.update("INSERT INTO event_registrations(registration_id, event_id, attendee_name, reference, registered_at, status, attendee_id) VALUES (?, ?, 'Duplicate', 'NEW-2', now(), 'confirmed', ?)",
                            UUID.randomUUID(), event, attendee)).hasMessageContaining("uq_active_registration_per_attendee_event");
                    assertThat(db.queryForObject("SELECT count(*) FROM event_registrations", Integer.class)).isEqualTo(4);
                } finally {
                    connection.setSchema(previousSchema);
                }
            }
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static void insertNotification(JdbcTemplate db, UUID event, UUID user, String type, String message) {
        db.update("INSERT INTO notifications(notification_id, recipient_user_id, event_id, type, event_name, message) VALUES (?, ?, ?, cast(? as notification_type), 'Migration event', ?)",
                UUID.randomUUID(), user, event, type, message);
    }
}
