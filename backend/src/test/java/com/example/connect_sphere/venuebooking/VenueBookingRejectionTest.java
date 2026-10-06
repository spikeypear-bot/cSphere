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
import org.junit.jupiter.api.Tag;
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
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class VenueBookingRejectionTest {
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
        jdbc.execute("DROP TRIGGER IF EXISTS vs04_fail_update ON venue_bookings");
        jdbc.execute("DROP FUNCTION IF EXISTS vs04_fail_update()");
        for (UUID id : eventIds) {
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?", id);
            jdbc.update("DELETE FROM events WHERE event_id=?", id);
        }
        for (UUID id : venueIds) jdbc.update("DELETE FROM venues WHERE venue_id=?", id);
    }
    @Test void rejectionPreservesDataAndOriginalReason() throws Exception {
        UUID id = booking(venue(), "pending");
        var before = bookings.get(id);
        String unchanged = jdbc.queryForObject("SELECT (to_jsonb(b) - 'status' - 'reject_reason')::text FROM venue_bookings b WHERE booking_id=?", String.class, id);
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content("{\"reason\":\"  Maintenance  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectReason").value("Maintenance"));
        assertThat(bookings.get(id).event()).isEqualTo(before.event());
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(b) - 'status' - 'reject_reason')::text FROM venue_bookings b WHERE booking_id=?", String.class, id)).isEqualTo(unchanged);
        assertThat(bookings.listPending()).noneMatch(b -> b.bookingId().equals(id));
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content("{\"reason\":\"Replacement\"}"))
                .andExpect(status().isConflict());
        assertThat(bookings.get(id).rejectReason()).isEqualTo("Maintenance");
        mvc.perform(patch("/api/venue-bookings/" + id + "/approve").with(user("vs").roles("VS")))
                .andExpect(status().isConflict());
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"})
    void invalidReasonLeavesBookingUntouched(String body) throws Exception {
        UUID id = booking(venue(), "pending");
        String before = snapshot(id);
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(snapshot(id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"approved","changed","rejected","cancelled"})
    void refusesNonPending(String state) throws Exception {
        UUID id = booking(venue(), state);
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content("{\"reason\":\"Unavailable\"}"))
                .andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(state);
    }
    @Test void missingAndUnauthorized() throws Exception {
        mvc.perform(patch("/api/venue-bookings/" + UUID.randomUUID() + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content("{\"reason\":\"Unavailable\"}"))
                .andExpect(status().isNotFound());
        UUID id = booking(venue(), "pending");
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("ec").roles("EC"))
                .contentType("application/json").content("{\"reason\":\"Unavailable\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void failedWriteRollsBackBothFields() throws Exception {
        UUID id = booking(venue(), "pending");
        String before = snapshot(id);
        jdbc.execute("CREATE FUNCTION vs04_fail_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Test failure'; END $$");
        jdbc.execute("CREATE TRIGGER vs04_fail_update BEFORE UPDATE ON venue_bookings FOR EACH ROW WHEN (OLD.booking_id = '" + id + "'::uuid) EXECUTE FUNCTION vs04_fail_update()");
        mvc.perform(patch("/api/venue-bookings/" + id + "/reject").with(user("vs").roles("VS"))
                .contentType("application/json").content("{\"reason\":\"Unavailable\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(snapshot(id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void competingDecisionsOnlySucceedOnce(boolean approve) throws Exception {
        UUID id = booking(venue(), "pending");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); try { bookings.reject(id, "First"); return "ok"; } catch (VenueBookingStateException ex) { return "conflict"; } });
            var second = pool.submit(() -> { start.await(); try { if (approve) bookings.approve(id); else bookings.reject(id, "Second"); return "ok"; } catch (VenueBookingStateException ex) { return "conflict"; } });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok", "conflict");
            var result = bookings.get(id);
            if (result.status().name().equals("rejected")) assertThat(result.rejectReason()).isIn("First", "Second");
            else assertThat(result.rejectReason()).isNull();
        }
    }
}
