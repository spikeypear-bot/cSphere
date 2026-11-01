package com.example.connect_sphere.venue;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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

/** Isolated schemas on real PostgreSQL; never cleans or resets the application schema. */
@Tag("DEV16")
@Tag("integration")
@SpringBootTest
class VenueSeedMigrationTest {
    @Autowired DataSource dataSource;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SQL_PATH = "db/migration/V61__seed_shared_venues.sql";

    private interface Scenario { void run(Connection c, String schema) throws Exception; }

    private void inSchema(Scenario scenario) throws Exception {
        inSchema("60", scenario);
    }

    private void inSchema(String target, Scenario scenario) throws Exception {
        String schema = "dev16_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrate(schema, target);
            try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try { scenario.run(c, schema); }
                finally { statement.execute("RESET search_path"); }
            }
        } finally {
            try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private Flyway migrate(String schema, String target) {
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target(target).load();
        flyway.migrate();
        return flyway;
    }

    private static String migration() throws Exception {
        return new ClassPathResource(SQL_PATH).getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode source() throws Exception {
        return JSON.readTree(Files.readString(Path.of("dev/seed/venues.json")));
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

    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }

    @Test
    @DisplayName("DEV16 (happy): fresh database maps all 20 venues and 129 structured hours without fabricating coordinates")
    void importsEverySourceFieldAndRestartIsANoop() throws Exception {
        inSchema((c, schema) -> {
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("0");
            migrate(schema, "61");
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("20");
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("129");
            for (JsonNode expected : source()) {
                try (var query = c.prepareStatement("SELECT * FROM venues WHERE venue_name=? AND venue_address=?")) {
                    query.setString(1, expected.get("venueName").asString());
                    query.setString(2, expected.get("venueAddress").asString());
                    try (var row = query.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getInt("venue_capacity")).isEqualTo(expected.get("venueCapacity").asInt());
                        assertThat(row.getInt("setup_minutes")).isEqualTo(expected.get("setupMinutes").asInt());
                        assertThat(row.getInt("turnaround_minutes")).isEqualTo(expected.get("turnaroundMinutes").asInt());
                        assertThat(row.getString("additional_information")).isEqualTo(expected.get("additionalInformation").asString());
                        assertThat(row.getObject("latitude")).isNull();
                        assertThat(row.getObject("longitude")).isNull();
                        for (String[] fields : List.of(new String[]{"supported_layouts", "supportedLayouts"},
                                new String[]{"venue_accessibilities", "venueAccessibilities"},
                                new String[]{"venue_facilities", "venueFacilities"})) {
                            var actual = (Object[]) row.getArray(fields[0]).getArray();
                            var expectedValues = new java.util.ArrayList<String>();
                            expected.get(fields[1]).forEach(v -> expectedValues.add(v.asString()));
                            assertThat(java.util.Arrays.stream(actual).map(Object::toString).toList()).containsExactlyElementsOf(expectedValues);
                        }
                        String id = row.getString("venue_id");
                        String schedule = value(c, "SELECT jsonb_agg(jsonb_build_object('dayOfWeek',day_of_week,'openTime',to_char(open_time,'HH24:MI'),'closeTime',to_char(close_time,'HH24:MI')) ORDER BY day_of_week)::text FROM venue_operating_hours WHERE venue_id='" + id + "'");
                        assertThat(JSON.readTree(schedule)).isEqualTo(expected.get("operatingHours"));
                        String summary = value(c, "SELECT string_agg((ARRAY['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'])[day_of_week] || ' ' || to_char(open_time,'HH24:MI') || '–' || to_char(close_time,'HH24:MI'), '; ' ORDER BY day_of_week) FROM venue_operating_hours WHERE venue_id='" + id + "'");
                        assertThat(row.getString("operating_information")).isEqualTo(summary);
                        assertThat(row.next()).isFalse();
                    }
                }
            }
            String venues = snapshot(c, "venues"), hours = snapshot(c, "venue_operating_hours");
            assertThat(migrate(schema, "61").migrate().migrationsExecuted).isZero();
            assertThat(snapshot(c, "venues")).isEqualTo(venues);
            assertThat(snapshot(c, "venue_operating_hours")).isEqualTo(hours);
            assertThat(value(c, "SELECT count(*) FROM flyway_schema_history WHERE version IN ('60','61') AND success")).isEqualTo("2");
            // Defend deterministic repeat execution independently of Flyway's history guard.
            c.setAutoCommit(false);
            execute(c, migration());
            c.commit();
            c.setAutoCommit(true);
            assertThat(snapshot(c, "venues")).isEqualTo(venues);
            assertThat(snapshot(c, "venue_operating_hours")).isEqualTo(hours);
        });
    }

    @Test
    @DisplayName("DEV16 (cross-cutting): matching existing venue, edits, hours, bookings and events survive upgrade unchanged")
    void skipsExistingRoomAndPreservesEveryExistingColumn() throws Exception {
        inSchema("59", (c, schema) -> {
            JsonNode first = source().get(0);
            UUID venue = UUID.randomUUID(), event = UUID.randomUUID();
            try (var insert = c.prepareStatement("INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information,additional_information,setup_minutes,turnaround_minutes) VALUES (?, ?, ?, 99, ARRAY['banquet'], 'User hours', 'User edit', 7, 9)")) {
                insert.setObject(1, venue);
                insert.setString(2, "  " + first.get("venueName").asString().toUpperCase(java.util.Locale.ROOT) + "  ");
                insert.setString(3, first.get("venueAddress").asString().replace(" ", "  "));
                insert.executeUpdate();
            }
            execute(c, "INSERT INTO venue_operating_hours VALUES ('" + UUID.randomUUID() + "','" + venue + "',7,'11:00','12:00')");
            execute(c, "INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status) VALUES ('" + event + "','Preserve','Test','2030-04-01 10:00+08','2030-04-01 11:00+08',20,'Room','pending')");
            execute(c, "INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES ('" + UUID.randomUUID() + "','" + venue + "','" + event + "','approved','2030-04-01 10:00+08','2030-04-01 11:00+08')");
            String existing = value(c, "SELECT to_jsonb(v)::text FROM venues v WHERE venue_id='" + venue + "'");
            String hours = snapshot(c, "venue_operating_hours"), bookings = snapshot(c, "venue_bookings"), events = snapshot(c, "events");
            migrate(schema, "61");
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("20");
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("125");
            assertThat(value(c, "SELECT (to_jsonb(v)-'latitude'-'longitude')::text FROM venues v WHERE venue_id='" + venue + "'")).isEqualTo(existing);
            assertThat(value(c, "SELECT latitude IS NULL AND longitude IS NULL FROM venues WHERE venue_id='" + venue + "'")).isEqualTo("t");
            assertThat(value(c, "SELECT jsonb_agg(to_jsonb(h) ORDER BY to_jsonb(h)::text)::text FROM venue_operating_hours h WHERE venue_id='" + venue + "'")).isEqualTo(hours);
            assertThat(snapshot(c, "venue_bookings")).isEqualTo(bookings);
            assertThat(snapshot(c, "events")).isEqualTo(events);
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): venue UUID collision aborts atomically with actionable counts and no Flyway success")
    void venueCollisionRollsBackEntireSeed() throws Exception {
        inSchema((c, schema) -> {
            String seedId = migration().split("VALUES\\s*\\(")[1].substring(1, 37);
            execute(c, "INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES ('" + seedId + "','Other room','Other address',30,ARRAY['theatre'],'Keep')");
            String before = snapshot(c, "venues");
            assertThatThrownBy(() -> migrate(schema, "61")).hasStackTraceContaining("Venue identity/UUID conflict").hasStackTraceContaining("imported=0").hasStackTraceContaining("rejected=1");
            assertThat(snapshot(c, "venues")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("0");
            assertThat(value(c, "SELECT count(*) FROM flyway_schema_history WHERE version='61'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): a seed UUID assigned to another room at the same address is still a collision")
    void uuidAtSameAddressDoesNotHideAnotherRoom() throws Exception {
        inSchema((c, schema) -> {
            String seedId = migration().split("VALUES\\s*\\(")[1].substring(1, 37);
            try (var insert = c.prepareStatement("INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES (?::uuid, 'Different bookable room', ?, 30,ARRAY['theatre'],'Keep')")) {
                insert.setString(1, seedId);
                insert.setString(2, source().get(0).get("venueAddress").asString());
                insert.executeUpdate();
            }
            String before = snapshot(c, "venues");
            assertThatThrownBy(() -> migrate(schema, "61")).hasStackTraceContaining("Venue identity/UUID conflict");
            assertThat(snapshot(c, "venues")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): duplicate existing room identities abort without overwriting or inserting venues")
    void ambiguousMatchesFailSafely() throws Exception {
        inSchema((c, schema) -> {
            for (int i = 0; i < 2; i++) {
                try (var insert = c.prepareStatement("INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES (?, ?, ?, 30,ARRAY['theatre'],'Keep')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, source().get(0).get("venueName").asString());
                    insert.setString(3, source().get(0).get("venueAddress").asString());
                    insert.executeUpdate();
                }
            }
            String before = snapshot(c, "venues");
            assertThatThrownBy(() -> migrate(schema, "61")).hasStackTraceContaining("Ambiguous existing venues");
            assertThat(snapshot(c, "venues")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): operating-hour UUID collision preserves the unrelated venue and aborts all imports")
    void hourCollisionFailsSafely() throws Exception {
        inSchema((c, schema) -> {
            UUID venue = UUID.randomUUID();
            String hourId = migration().split("INSERT INTO dev16_seed_hours")[1].split("VALUES\\s*\\(")[1].substring(1, 37);
            execute(c, "INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES ('" + venue + "','Other','Elsewhere',30,ARRAY['theatre'],'Keep')");
            execute(c, "INSERT INTO venue_operating_hours VALUES ('" + hourId + "','" + venue + "',1,'09:00','10:00')");
            String venues = snapshot(c, "venues"), hours = snapshot(c, "venue_operating_hours");
            assertThatThrownBy(() -> migrate(schema, "61")).hasStackTraceContaining("Operating-hour UUID collision");
            assertThat(snapshot(c, "venues")).isEqualTo(venues);
            assertThat(snapshot(c, "venue_operating_hours")).isEqualTo(hours);
        });
    }

    @Test
    @DisplayName("DEV16 (happy): distinct rooms sharing an address and coordinates remain separate records")
    void sharedLocationDoesNotMergeRooms() throws Exception {
        inSchema((c, schema) -> {
            String sql = migration();
            for (int i = 0; i < 2; i++) sql = sql.replace(source().get(i).get("venueAddress").asString(), "Shared campus address");
            sql = sql.replace("NULL, NULL)", "1.3, 103.8)");
            c.setAutoCommit(false);
            execute(c, sql);
            c.commit();
            c.setAutoCommit(true);
            assertThat(value(c, "SELECT count(DISTINCT venue_id) FROM venues WHERE venue_address='Shared campus address' AND latitude=1.3 AND longitude=103.8")).isEqualTo("2");
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("20");
        });
    }

    @Test
    @DisplayName("DEV16 (boundary): legacy address-as-name venue is preserved without creating a duplicate room")
    void recognisesLegacyAddressAsName() throws Exception {
        inSchema((c, schema) -> {
            String address = source().get(0).get("venueAddress").asString();
            UUID venue = UUID.randomUUID();
            try (var insert = c.prepareStatement("INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES (?, ?, ?, 31,ARRAY['theatre'],'Legacy hours')")) {
                insert.setObject(1, venue);
                insert.setString(2, address);
                insert.setString(3, address);
                insert.executeUpdate();
            }
            String before = value(c, "SELECT to_jsonb(v)::text FROM venues v WHERE venue_id='" + venue + "'");
            migrate(schema, "61");
            assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("20");
            assertThat(value(c, "SELECT to_jsonb(v)::text FROM venues v WHERE venue_id='" + venue + "'")).isEqualTo(before);
            assertThat(value(c, "SELECT count(*) FROM venue_operating_hours WHERE venue_id='" + venue + "'")).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): one legacy record cannot silently absorb several rooms at a shared address")
    void ambiguousLegacyLocationFailsWithoutImporting() throws Exception {
        inSchema((c, schema) -> {
            execute(c, "INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,supported_layouts,operating_information) VALUES ('" + UUID.randomUUID() + "','Shared campus address','Shared campus address',30,ARRAY['theatre'],'Keep')");
            String before = snapshot(c, "venues");
            String sql = migration();
            for (int i = 0; i < 2; i++) sql = sql.replace(source().get(i).get("venueAddress").asString(), "Shared campus address");
            final String conflictingSql = sql;
            c.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> execute(c, conflictingSql)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("Existing venue matches multiple seed rooms");
                c.rollback();
                assertThat(snapshot(c, "venues")).isEqualTo(before);
                assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("0");
            } finally { c.rollback(); c.setAutoCommit(true); }
        });
    }

    @Test
    @DisplayName("DEV16 (boundary): nullable coordinates accept boundaries and reject out-of-range or nonfinite values")
    void coordinateConstraintsProtectAllWrites() throws Exception {
        inSchema((c, schema) -> {
            migrate(schema, "61");
            c.setAutoCommit(false);
            try {
                for (String valid : List.of("latitude=-90,longitude=-180", "latitude=90,longitude=180", "latitude=NULL,longitude=NULL"))
                    execute(c, "UPDATE venues SET " + valid);
                String before = snapshot(c, "venues");
                for (String invalid : List.of("latitude=-90.0001", "latitude=90.0001", "longitude=-180.0001", "longitude=180.0001", "latitude='NaN'", "longitude='Infinity'")) {
                    var savepoint = c.setSavepoint();
                    assertThatThrownBy(() -> execute(c, "UPDATE venues SET " + invalid)).isInstanceOf(SQLException.class);
                    c.rollback(savepoint);
                    assertThat(snapshot(c, "venues")).isEqualTo(before);
                }
            } finally { c.rollback(); c.setAutoCommit(true); }
        });
    }

    @Test
    @DisplayName("DEV16 (failure): invalid seed SQL is rejected transactionally before catalogue records are written")
    void invalidStagedDataLeavesNothingImported() throws Exception {
        inSchema((c, schema) -> {
            c.setAutoCommit(false);
            try {
                String invalid = migration().replace(", 40, ARRAY['classroom','boardroom']", ", 0, ARRAY['classroom','boardroom']");
                assertThat(invalid).isNotEqualTo(migration());
                assertThatThrownBy(() -> execute(c, invalid)).isInstanceOf(SQLException.class);
                c.rollback();
                assertThat(value(c, "SELECT count(*) FROM venues")).isEqualTo("0");
                assertThat(value(c, "SELECT count(*) FROM venue_operating_hours")).isEqualTo("0");
            } finally { c.rollback(); c.setAutoCommit(true); }
        });
    }
}
