package com.example.connect_sphere.venue;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class VenueStaffNotesMigrationTest {
    @Autowired DataSource dataSource;
    private interface Scenario { void run(Connection connection, String schema) throws Exception; }
    private JsonNode notes() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(Path.of("dev/seed/venue_staff_notes.json")));
    }
    private Flyway migrate(String schema, String target) {
        var flyway=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target).load();
        flyway.migrate(); return flyway;
    }
    private void inSchema(Scenario scenario) throws Exception {
        String schema="dev16_notes_"+UUID.randomUUID().toString().replace("-","");
        try {
            // The version just before V64 (V63, AT09, sits between), so only V64 runs inside a scenario.
            migrate(schema,"63");
            try(var c=dataSource.getConnection();var sql=c.createStatement()) {
                sql.execute("SET search_path TO "+schema);
                try { scenario.run(c,schema); } finally { sql.execute("RESET search_path"); }
            }
        } finally {
            try(var c=dataSource.getConnection();var sql=c.createStatement()) { sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
        }
    }
    private void execute(Connection c,String sql) throws Exception { try(var statement=c.createStatement()) { statement.execute(sql); } }
    private String value(Connection c,String sql) throws Exception {
        try(var statement=c.createStatement();var rows=statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private String snapshot(Connection c,String table) throws Exception {
        return value(c,"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t");
    }
    private String facts(Connection c) throws Exception {
        return value(c,"SELECT jsonb_agg(to_jsonb(v)-'additional_information' ORDER BY venue_id)::text FROM venues v");
    }
    private String id(JsonNode row) { return row.get("venueId").asString(); }
    private void cloneRoom(Connection c,String original,String copy,boolean legacy) throws Exception {
        execute(c,"INSERT INTO venues SELECT (jsonb_populate_record(NULL::venues,to_jsonb(v)||jsonb_build_object('venue_id','"+copy+"'"
                +(legacy ? ",'venue_name',v.venue_address" : "")+"))).* FROM venues v WHERE venue_id='"+original+"'");
    }

    @Test
    @DisplayName("DEV16 (happy): all 20 staff notes are persisted while every other venue field and booking remains unchanged")
    void correctsNotesOnlyAndRestartsSafely() throws Exception {
        inSchema((c,schema)->{
            var source=notes();
            String event=UUID.randomUUID().toString();
            execute(c,"INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status) VALUES ('"+event+"','Preserve','Notes','2030-04-01 10:00+08','2030-04-01 11:00+08',20,'Room','pending')");
            execute(c,"INSERT INTO venue_bookings(booking_id,venue_id,event_id,status) VALUES ('"+UUID.randomUUID()+"','"+id(source.get(0))+"','"+event+"','approved')");
            String before=facts(c), hours=snapshot(c,"venue_operating_hours"), events=snapshot(c,"events"), bookings=snapshot(c,"venue_bookings");
            migrate(schema,"64");
            for(var note:source) {
                try(var query=c.prepareStatement("SELECT additional_information FROM venues WHERE venue_id=?::uuid")) {
                    query.setString(1,id(note));
                    try(var row=query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(note.get("additionalInformation").asString()); }
                }
            }
            assertThat(facts(c)).isEqualTo(before);
            assertThat(snapshot(c,"venue_operating_hours")).isEqualTo(hours);
            assertThat(snapshot(c,"events")).isEqualTo(events);
            assertThat(snapshot(c,"venue_bookings")).isEqualTo(bookings);
            String after=snapshot(c,"venues");
            assertThat(migrate(schema,"64").migrate().migrationsExecuted).isZero();
            execute(c,new ClassPathResource("db/migration/V64__venue_staff_planning_notes.sql").getContentAsString(StandardCharsets.UTF_8));
            assertThat(snapshot(c,"venues")).isEqualTo(after);
            assertThat(source.get(15).get("additionalInformation").asString()).contains("illustrative","not an operator-approved capacity");
            assertThat(source.get(19).get("additionalInformation").asString()).contains("have not been recorded");
        });
    }

    @Test
    @DisplayName("DEV16 (conflict): staff edits, blank notes and changed identity are preserved while retained legacy UUIDs are supported")
    void preservesStaffChangesAndLegacyIdentity() throws Exception {
        inSchema((c,schema)->{
            var source=notes();
            execute(c,"UPDATE venues SET additional_information='Staff: use side entrance' WHERE venue_id='"+id(source.get(0))+"'");
            execute(c,"UPDATE venues SET additional_information='' WHERE venue_id='"+id(source.get(1))+"'");
            execute(c,"UPDATE venues SET additional_information=NULL WHERE venue_id='"+id(source.get(2))+"'");
            execute(c,"UPDATE venues SET venue_name='Staff renamed room' WHERE venue_id='"+id(source.get(3))+"'");
            execute(c,"UPDATE venues SET venue_address='Staff changed address' WHERE venue_id='"+id(source.get(4))+"'");
            String original=id(source.get(5)), legacy=UUID.randomUUID().toString();
            cloneRoom(c,original,legacy,true);
            execute(c,"DELETE FROM venue_operating_hours WHERE venue_id='"+original+"'");
            execute(c,"DELETE FROM venues WHERE venue_id='"+original+"'");
            String before=facts(c);
            migrate(schema,"64");
            assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+id(source.get(0))+"'")).isEqualTo("Staff: use side entrance");
            assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+id(source.get(1))+"'")).isEmpty();
            assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+id(source.get(2))+"'")).isNull();
            for(int i=3;i<=4;i++) assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+id(source.get(i))+"'")).isEqualTo(source.get(i).get("previousAdditionalInformation").asString());
            assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+legacy+"'")).isEqualTo(source.get(5).get("additionalInformation").asString());
            assertThat(facts(c)).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("DEV16 (boundary): ambiguous duplicate rooms and missing venues are left untouched without creating records")
    void skipsAmbiguousAndMissingRooms() throws Exception {
        inSchema((c,schema)->{
            var source=notes(); String original=id(source.get(0)), duplicate=UUID.randomUUID().toString(), missing=id(source.get(1));
            cloneRoom(c,original,duplicate,false);
            execute(c,"DELETE FROM venue_operating_hours WHERE venue_id='"+missing+"'");
            execute(c,"DELETE FROM venues WHERE venue_id='"+missing+"'");
            String before=facts(c);
            migrate(schema,"64");
            for(String room:new String[]{original,duplicate}) assertThat(value(c,"SELECT additional_information FROM venues WHERE venue_id='"+room+"'")).isEqualTo(source.get(0).get("previousAdditionalInformation").asString());
            assertThat(value(c,"SELECT count(*) FROM venues WHERE venue_id='"+missing+"'")).isEqualTo("0");
            assertThat(facts(c)).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("DEV16 (failure): a database error rolls back every note correction and leaves no successful migration history")
    void rollsBackCompletely() throws Exception {
        inSchema((c,schema)->{
            String before=snapshot(c,"venues"), history=snapshot(c,"flyway_schema_history");
            execute(c,"CREATE FUNCTION reject_staff_note() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Test write refusal'; END $$");
            execute(c,"CREATE TRIGGER reject_staff_note BEFORE UPDATE OF additional_information ON venues FOR EACH ROW EXECUTE FUNCTION reject_staff_note()");
            assertThatThrownBy(()->migrate(schema,"64")).isInstanceOf(FlywayException.class);
            assertThat(snapshot(c,"venues")).isEqualTo(before);
            assertThat(snapshot(c,"flyway_schema_history")).isEqualTo(history);
        });
    }
}
