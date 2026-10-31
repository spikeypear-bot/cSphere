package com.example.connect_sphere.venue;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("DEV16")
@Tag("integration")
@SpringBootTest
class VenueCoordinateMigrationTest {
    @Autowired DataSource dataSource;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private interface Scenario { void run(Connection c, String schema) throws Exception; }

    private void inSchema(String target, Scenario scenario) throws Exception {
        String schema = "dev16_coords_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrate(schema, target);
            try (var c = dataSource.getConnection(); var sql = c.createStatement()) {
                sql.execute("SET search_path TO " + schema);
                try { scenario.run(c, schema); }
                finally { sql.execute("RESET search_path"); }
            }
        } finally {
            try (var c = dataSource.getConnection(); var sql = c.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private Flyway migrate(String schema, String target) {
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target).load();
        flyway.migrate();
        return flyway;
    }

    private static JsonNode mappings() throws Exception {
        return JSON.readTree(Files.readString(Path.of("dev/seed/venue_coordinates.json")));
    }

    private static String migration() throws Exception {
        return new ClassPathResource("db/migration/V62__map_existing_venue_coordinates.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }

    private static String value(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static String snapshot(Connection c, String table) throws SQLException {
        return value(c, "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t");
    }

    private static String venueFacts(Connection c) throws SQLException {
        return value(c, "SELECT jsonb_agg(to_jsonb(v)-'latitude'-'longitude' ORDER BY venue_id)::text FROM venues v");
    }

    @Test
    @DisplayName("DEV16 (happy): all 20 existing rooms receive exact reference coordinates while all other venue and booking facts remain unchanged")
    void mapsEveryRoomPreservesRelationshipsAndRestartsSafely() throws Exception {
        inSchema("61", (c, schema) -> {
            String venue = mappings().get(0).get("venueId").asString();
            UUID event = UUID.randomUUID();
            execute(c, "INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status) VALUES ('" + event + "','Preserve','Coordinates','2030-04-01 10:00+08','2030-04-01 11:00+08',20,'Room','pending')");
            execute(c, "INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES ('" + UUID.randomUUID() + "','" + venue + "','" + event + "','approved','2030-04-01 10:00+08','2030-04-01 11:00+08')");
            String facts = venueFacts(c), hours = snapshot(c, "venue_operating_hours"),
                    events = snapshot(c, "events"), bookings = snapshot(c, "venue_bookings");
            migrate(schema, "62");
            assertThat(value(c, "SELECT count(*) FROM venues WHERE latitude IS NOT NULL AND longitude IS NOT NULL")).isEqualTo("20");
            assertThat(value(c, "SELECT count(DISTINCT (latitude,longitude)) FROM venues")).isEqualTo("13");
            for (var expected : mappings()) {
                try (var sql = c.prepareStatement("SELECT latitude,longitude FROM venues WHERE venue_id=?::uuid")) {
                    sql.setString(1, expected.get("venueId").asString());
                    try (var row = sql.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getDouble(1)).isEqualTo(expected.get("latitude").asDouble());
                        assertThat(row.getDouble(2)).isEqualTo(expected.get("longitude").asDouble());
                    }
                }
            }
            assertThat(venueFacts(c)).isEqualTo(facts);
            assertThat(snapshot(c, "venue_operating_hours")).isEqualTo(hours);
            assertThat(snapshot(c, "events")).isEqualTo(events);
            assertThat(snapshot(c, "venue_bookings")).isEqualTo(bookings);
            String after = snapshot(c, "venues");
            assertThat(migrate(schema, "62").migrate().migrationsExecuted).isZero();
            c.setAutoCommit(false);
            try { execute(c, migration()); c.commit(); }
            finally { c.rollback(); c.setAutoCommit(true); }
            assertThat(snapshot(c, "venues")).isEqualTo(after);
            assertThat(value(c, "SELECT count(*) FROM flyway_schema_history WHERE version='62' AND success")).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("DEV16 (boundary): saved coordinates and conflicting partial pairs are preserved while compatible missing components are filled")
    void preservesUserCoordinatesAndNeverCreatesMixedPairs() throws Exception {
        inSchema("61", (c, schema) -> {
            var rows = mappings();
            String first = rows.get(0).get("venueId").asString(), second = rows.get(1).get("venueId").asString();
            execute(c, "UPDATE venues SET latitude=NULL,longitude=104.123 WHERE venue_id='" + first + "'");
            execute(c, "UPDATE venues SET latitude=1.2,longitude=103.1 WHERE venue_id='" + second + "'");
            String third = rows.get(2).get("venueId").asString(), fourth = rows.get(3).get("venueId").asString();
            execute(c, "UPDATE venues SET latitude=" + rows.get(2).get("latitude").asDouble() + " WHERE venue_id='" + third + "'");
            execute(c, "UPDATE venues SET longitude=" + rows.get(3).get("longitude").asDouble() + " WHERE venue_id='" + fourth + "'");
            UUID unrelated = UUID.randomUUID();
            execute(c, "INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES ('" + unrelated + "','Unrelated room','Another building',50,ARRAY['theatre'],'Keep')");
            String facts = venueFacts(c);
            String saved = value(c, "SELECT jsonb_agg(to_jsonb(v) ORDER BY venue_id)::text FROM venues v WHERE venue_id IN ('" + first + "','" + second + "','" + unrelated + "')");
            migrate(schema, "62");
            assertThat(value(c, "SELECT jsonb_agg(to_jsonb(v) ORDER BY venue_id)::text FROM venues v WHERE venue_id IN ('" + first + "','" + second + "','" + unrelated + "')")).isEqualTo(saved);
            assertThat(value(c, "SELECT count(*) FROM venues WHERE venue_id IN ('" + third + "','" + fourth + "') AND latitude=" + rows.get(2).get("latitude").asDouble() + " AND longitude=" + rows.get(2).get("longitude").asDouble())).isEqualTo("2");
            assertThat(venueFacts(c)).isEqualTo(facts);
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("21");
        });
    }

    @Test
    @DisplayName("DEV16 (cross-cutting): an existing legacy room retained by V61 under another UUID receives only its missing coordinates")
    void mapsLegacyIdentityWithoutReplacingItsUuid() throws Exception {
        inSchema("60", (c, schema) -> {
            var first = mappings().get(0);
            UUID existing = UUID.randomUUID();
            try (var sql = c.prepareStatement("INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information,additional_information) VALUES (?, ?, ?, 77, ARRAY['banquet'], 'User hours', 'User edit')")) {
                sql.setObject(1, existing);
                sql.setString(2, "  " + first.get("venueAddress").asString().toUpperCase(java.util.Locale.ROOT) + "  ");
                sql.setString(3, first.get("venueAddress").asString().replace(" ", "  "));
                sql.executeUpdate();
            }
            migrate(schema, "61");
            String facts = venueFacts(c), hours = snapshot(c, "venue_operating_hours");
            migrate(schema, "62");
            assertThat(venueFacts(c)).isEqualTo(facts);
            assertThat(snapshot(c, "venue_operating_hours")).isEqualTo(hours);
            assertThat(value(c, "SELECT latitude::text||','||longitude::text FROM venues WHERE venue_id='" + existing + "'")).isEqualTo("1.29505,103.84906");
            assertThat(value(c, "SELECT count(*) FROM venues WHERE venue_id='" + first.get("venueId").asString() + "'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): a UUID belonging to a different room aborts the entire coordinate mapping")
    void refusesUuidCollisionWithoutPartialUpdates() throws Exception {
        inSchema("61", (c, schema) -> {
            execute(c, "UPDATE venues SET venue_name='Different physical room' WHERE venue_id='" + mappings().get(0).get("venueId").asString() + "'");
            String before = snapshot(c, "venues");
            assertThatThrownBy(() -> migrate(schema, "62")).hasStackTraceContaining("Coordinate identity/UUID conflict").hasStackTraceContaining("filled=0");
            assertThat(snapshot(c, "venues")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM flyway_schema_history WHERE version='62'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): multiple existing matches fail atomically instead of updating an arbitrary room")
    void refusesAmbiguousTargets() throws Exception {
        inSchema("61", (c, schema) -> {
            execute(c, "INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) SELECT '" + UUID.randomUUID() + "'::uuid,venue_name,venue_address,50,ARRAY['theatre'],'Keep' FROM venues WHERE venue_id='" + mappings().get(0).get("venueId").asString() + "'");
            String before = snapshot(c, "venues");
            assertThatThrownBy(() -> migrate(schema, "62")).hasStackTraceContaining("Ambiguous coordinate targets");
            assertThat(snapshot(c, "venues")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM flyway_schema_history WHERE version='62'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (negative): deleted rooms stay deleted and coordinate mapping never creates venues")
    void skipsMissingRoomsWithoutRecreatingThem() throws Exception {
        inSchema("61", (c, schema) -> {
            String missing = mappings().get(0).get("venueId").asString();
            execute(c, "DELETE FROM venues WHERE venue_id='" + missing + "'");
            String facts = venueFacts(c);
            migrate(schema, "62");
            assertThat(venueFacts(c)).isEqualTo(facts);
            assertThat(value(c, "SELECT count(*) FROM venues WHERE latitude IS NOT NULL AND longitude IS NOT NULL")).isEqualTo("19");
            assertThat(value(c, "SELECT count(*) FROM venues WHERE venue_id='" + missing + "'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (failure): invalid coordinate SQL is rejected before any venue coordinate changes")
    void rejectsInvalidStagedCoordinates() throws Exception {
        inSchema("61", (c, schema) -> {
            String before = snapshot(c, "venues");
            String invalid = migration().replace(", 1.29505, 103.84906)", ", 91, 103.84906)");
            assertThat(invalid).isNotEqualTo(migration());
            c.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> execute(c, invalid)).isInstanceOf(SQLException.class);
                c.rollback();
                assertThat(snapshot(c, "venues")).isEqualTo(before);
            } finally { c.rollback(); c.setAutoCommit(true); }
        });
    }
}
