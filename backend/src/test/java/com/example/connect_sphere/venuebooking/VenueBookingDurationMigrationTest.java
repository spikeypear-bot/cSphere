package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@Tag("integration")
@Tag("VS-C2")
@SpringBootTest
class VenueBookingDurationMigrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradesIncomingV26WithoutLosingBookingWindowsOrLifecycleNotifications() throws Exception {
        String schema = "vsc2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema);
        UUID venue = UUID.randomUUID(), event = UUID.randomUUID(), booking = UUID.randomUUID(), user = UUID.randomUUID();
        try {
            config.target("26").load().migrate();
            try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
                sql.execute("SET search_path TO " + schema);
                try {
                    sql.execute("INSERT INTO users(user_id,hashed_password,email,username,role) VALUES ('" + user + "','test','upgrade@test','upgrade','ec')");
                    sql.execute("INSERT INTO venues(venue_id,venue_address,operating_information,supported_layouts,venue_capacity,setup_minutes,turnaround_minutes) VALUES ('" + venue + "','Upgrade hall','Daily',ARRAY['classroom'],100,30,45)");
                    sql.execute("INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status) VALUES ('" + event + "','Upgrade','Test','2030-04-01 08:00+08','2030-04-01 18:00+08',50,'Room','pending')");
                    sql.execute("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES ('" + booking + "','" + venue + "','" + event + "','approved','2030-04-01 10:00+08','2030-04-01 12:00+08')");
                    sql.execute("INSERT INTO notifications(notification_id,recipient_user_id,event_id,type,event_name) VALUES ('" + UUID.randomUUID() + "','" + user + "','" + event + "','event_safety_approved','Upgrade')");
                } finally {
                    sql.execute("RESET search_path");
                }
            }
            config.target("28").load().migrate();
            try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
                sql.execute("SET search_path TO " + schema);
                try {
                    try (var rows = sql.executeQuery("SELECT start_datetime,end_datetime,setup_duration_minutes,turnaround_duration_minutes FROM venue_bookings WHERE booking_id='" + booking + "'")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getObject(1, java.time.OffsetDateTime.class)).isEqualTo(java.time.OffsetDateTime.parse("2030-04-01T10:00:00+08:00"));
                        assertThat(rows.getObject(2, java.time.OffsetDateTime.class)).isEqualTo(java.time.OffsetDateTime.parse("2030-04-01T12:00:00+08:00"));
                        assertThat(rows.getInt(3)).isEqualTo(30);
                        assertThat(rows.getInt(4)).isEqualTo(45);
                    }
                    for (String type : new String[]{"event_confirmed", "event_returned_to_planning", "event_safety_approved", "venue_timing_changed", "venue_timing_conflict"}) {
                        sql.execute("INSERT INTO notifications(notification_id,recipient_user_id,event_id,venue_booking_id,type,event_name) VALUES ('" + UUID.randomUUID() + "','" + user + "','" + event + "','" + booking + "','" + type + "','Upgrade')");
                    }
                    try (var rows = sql.executeQuery("SELECT count(*) FROM notifications")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(6);
                    }
                } finally {
                    sql.execute("RESET search_path");
                }
            }
        } finally {
            try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test void backfillPreservesEveryStatusAndEnforcesSnapshotConstraints() throws Exception {
        String migration = new ClassPathResource("db/migration/V27__venue_booking_duration_snapshots.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "vsc2_migration_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("SET LOCAL search_path TO " + schema);
                sql.execute("CREATE TABLE venues (venue_id integer PRIMARY KEY, setup_minutes integer NOT NULL, turnaround_minutes integer NOT NULL)");
                sql.execute("CREATE TABLE venue_bookings (booking_id integer PRIMARY KEY, venue_id integer REFERENCES venues, status text, booking_notes text)");
                sql.execute("INSERT INTO venues VALUES (1,30,45),(2,0,0),(3,10080,10080)");
                sql.execute("""
                        INSERT INTO venue_bookings VALUES
                        (1,1,'approved','keep approved'),(2,1,'pending','keep pending'),
                        (3,1,'rejected','keep rejected'),(4,1,'cancelled','keep cancelled'),
                        (5,1,'changed','keep changed'),(6,2,'approved','zero'),(7,3,'approved','maximum')
                        """);
                sql.execute("CREATE TABLE before_bookings AS SELECT * FROM venue_bookings");
                sql.execute(migration);
                try (var rows = sql.executeQuery("""
                        SELECT count(*) FROM venue_bookings b JOIN before_bookings old USING(booking_id)
                        JOIN venues v ON b.venue_id=v.venue_id
                        WHERE (to_jsonb(b)-'setup_duration_minutes'-'turnaround_duration_minutes')=to_jsonb(old)
                          AND b.setup_duration_minutes=v.setup_minutes AND b.turnaround_duration_minutes=v.turnaround_minutes
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(7);
                }
                sql.execute("UPDATE venues SET setup_minutes=0,turnaround_minutes=0 WHERE venue_id=1");
                try (var rows = sql.executeQuery("SELECT setup_duration_minutes,turnaround_duration_minutes FROM venue_bookings WHERE booking_id=1")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(30);
                    assertThat(rows.getInt(2)).isEqualTo(45);
                }
                for (String column : new String[]{"setup_duration_minutes", "turnaround_duration_minutes"}) {
                    for (String invalid : new String[]{"-1", "10081", "NULL"}) {
                        var savepoint = connection.setSavepoint();
                        assertThatThrownBy(() -> sql.execute("UPDATE venue_bookings SET " + column + "=" + invalid + " WHERE booking_id=1"))
                                .isInstanceOf(SQLException.class);
                        connection.rollback(savepoint);
                    }
                }
                sql.execute("INSERT INTO venue_bookings(booking_id,venue_id,status) VALUES (8,1,'pending')");
                try (var rows = sql.executeQuery("SELECT setup_duration_minutes,turnaround_duration_minutes FROM venue_bookings WHERE booking_id=8")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                    assertThat(rows.getInt(2)).isZero();
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
