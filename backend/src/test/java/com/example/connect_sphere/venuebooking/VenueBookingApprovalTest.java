package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.service.VenueBookingService;
import com.example.connect_sphere.venuebooking.service.VenueBookingStateException;

/** Committed isolated fixtures let each concurrent request use its own real transaction. */
@SpringBootTest
@AutoConfigureMockMvc
class VenueBookingApprovalTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired VenueBookingService bookings;
    final List<UUID> venueIds = new ArrayList<>();
    final List<UUID> eventIds = new ArrayList<>();

    UUID venue() {
        UUID id = venues.createVenue(new CreateVenueDto("VS03 " + UUID.randomUUID(), 200,
                List.of(VenueLayout.classroom), "Daily", null, null, null)).venueId();
        venueIds.add(id);
        return id;
    }
    UUID booking(UUID venueId, String status, String start, String end) {
        UUID event = UUID.randomUUID();
        eventIds.add(event);
        jdbc.update("""
                INSERT INTO events (event_id,event_name,purpose,start_datetime,end_datetime,
                    expected_attendance,venue_requirements,accessibility_needs,status)
                VALUES (?, 'VS03 Workshop', 'Test', ?::timestamptz, ?::timestamptz,
                    100,'Seating','{}'::accessibilities[],'pending')
                """, event, start, end);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes,suitability_note)
                VALUES (?,?,?,?::venue_booking_status,'Preserve notes','Preserve justification')
                """, id, venueId, event, status);
        return id;
    }
    UUID booking(UUID venue, String status) {
        return booking(venue, status, "2027-04-01T09:00:00+08:00", "2027-04-01T12:00:00+08:00");
    }
    String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?", String.class, id);
    }
    String snapshot(UUID id) {
        return jdbc.queryForObject("SELECT (to_jsonb(b) - 'status')::text FROM venue_bookings b WHERE booking_id=?", String.class, id);
    }
    @AfterEach void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS vs03_fail_update ON venue_bookings");
        jdbc.execute("DROP FUNCTION IF EXISTS vs03_fail_update()");
        for (UUID id : eventIds) {
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?", id);
            jdbc.update("DELETE FROM events WHERE event_id=?", id);
        }
        for (UUID id : venueIds) jdbc.update("DELETE FROM venues WHERE venue_id=?", id);
    }
    @Test void migrationRenamesExistingCommittedRowsWithoutChangingTheirData() throws Exception {
        String migration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V16__rename_venue_booking_confirmed_to_approved.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                String schema = "vs03_migration_" + UUID.randomUUID().toString().replace("-", "");
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET LOCAL search_path TO " + schema);
                statement.execute("CREATE TYPE venue_booking_status AS ENUM ('pending','confirmed','changed','rejected','cancelled')");
                statement.execute("CREATE TABLE bookings (id integer, status venue_booking_status, notes text)");
                statement.execute("INSERT INTO bookings VALUES (1,'confirmed','preserved'), (2,'pending','pending notes')");
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT id,status::text,notes FROM bookings ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).isEqualTo("approved");
                    assertThat(rows.getString(3)).isEqualTo("preserved");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(2)).isEqualTo("pending");
                    assertThat(rows.getString(3)).isEqualTo("pending notes");
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test void approvalPreservesRecordAndRelationshipsAndLeavesPendingQueue() throws Exception {
        UUID venue = venue(), id = booking(venue, "pending");
        String before = snapshot(id);
        var details = bookings.get(id);
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.bookingId").value(id.toString()))
                .andExpect(jsonPath("$.venue.venueId").value(venue.toString()))
                .andExpect(jsonPath("$.event.eventId").value(details.event().eventId().toString()));
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(bookings.get(id).event()).isEqualTo(details.event());
        assertThat(bookings.listPending()).noneMatch(b -> b.bookingId().equals(id));
        mvc.perform(get("/api/venue-staff/booking-requests").with(user("vs").roles("VS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].bookingId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(id.toString()))));
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isConflict());
    }
    @ParameterizedTest @ValueSource(strings={"approved","changed","rejected","cancelled"})
    void refusesNonPending(String state) throws Exception {
        UUID id = booking(venue(), state);
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(state);
    }
    @Test void missingAndInvalidIds() throws Exception {
        mvc.perform(patch("/api/venue-bookings/" + UUID.randomUUID() + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/venue-bookings/invalid/approve").with(user("vs").roles("VS")))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"EC","EO","TECHNICIAN","ATTENDEE"})
    void onlyVenueStaffMayApprove(String role) throws Exception {
        UUID id = booking(venue(), "pending");
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("other").roles(role)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(id)).isEqualTo("pending");
    }
    @Test void anonymousCannotApprove() throws Exception {
        UUID id = booking(venue(), "pending");
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve")).andExpect(status().isUnauthorized());
        assertThat(statusOf(id)).isEqualTo("pending");
    }
    @Test void conflictsBlockButBackToBackAndOtherVenuesRemainAvailable() throws Exception {
        UUID venue = venue();
        booking(venue, "approved");
        UUID conflict = booking(venue, "pending");
        mvc.perform(patch("/api/venue-bookings/" + conflict + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isConflict());
        assertThat(statusOf(conflict)).isEqualTo("pending");
        UUID adjacent = booking(venue, "pending", "2027-04-01T12:00:00+08:00", "2027-04-01T13:00:00+08:00");
        assertThat(bookings.approve(adjacent).status().name()).isEqualTo("approved");
        assertThat(bookings.approve(booking(venue(), "pending")).status().name()).isEqualTo("approved");
    }
    @Test void failedDatabaseWriteRollsBack() throws Exception {
        UUID id = booking(venue(), "pending");
        String before = snapshot(id);
        jdbc.execute("CREATE FUNCTION vs03_fail_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Test failure'; END $$");
        jdbc.execute("CREATE TRIGGER vs03_fail_update BEFORE UPDATE ON venue_bookings FOR EACH ROW WHEN (OLD.booking_id = '" + id + "'::uuid) EXECUTE FUNCTION vs03_fail_update()");
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").exists());
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(snapshot(id)).isEqualTo(before);
    }
    void race(UUID first, UUID second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(id -> pool.submit(() -> {
                start.await();
                try { bookings.approve(id); return "approved"; }
                catch (VenueBookingStateException ex) { return "conflict"; }
            })).toList();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("approved", "conflict");
        }
    }
    @Test void concurrentDuplicateApprovalSucceedsOnlyOnce() throws Exception {
        UUID id = booking(venue(), "pending");
        race(id, id);
        assertThat(statusOf(id)).isEqualTo("approved");
    }
    @Test void concurrentOverlappingApprovalsCommitOnlyOneBooking() throws Exception {
        UUID venue = venue(), a = booking(venue, "pending"), b = booking(venue, "pending");
        race(a, b);
        assertThat(List.of(statusOf(a), statusOf(b))).containsExactlyInAnyOrder("approved", "pending");
    }
}
