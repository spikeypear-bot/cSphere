package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

/** Real PostgreSQL fixtures roll back; no production creation/update workflow is added. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(username = "vs16-reader", roles = "VS")
class VenueBookingReadTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;

    private UUID venue() {
        UUID id = venues.createVenue(new CreateVenueDto("VS16 venue", 200,
                List.of(VenueLayout.classroom), "Daily", null, null, null)).venueId();
        entityManager.flush();
        return id;
    }

    private UUID event() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (event_id,event_name,purpose,start_datetime,end_datetime,
                    expected_attendance,venue_requirements,accessibility_needs,status)
                VALUES (?, 'Workshop', 'Test', '2026-09-24T14:00:00+08:00',
                    '2026-09-24T17:00:00+08:00',120,'Classroom seating',
                    ARRAY['step_free_access']::accessibilities[],'confirmed')
                """, id);
        return id;
    }

    private UUID booking(UUID venueId, UUID eventId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes)
                VALUES (?,?,?,cast(? as venue_booking_status),'Preserve notes')
                """, id, venueId, eventId, status);
        return id;
    }

    private String snapshot(String table) {
        // Table names are fixed test constants, never user input.
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                + table + " t", String.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "confirmed", "changed", "rejected", "cancelled"})
    void readsCurrentLinkedEventWithoutChangingAnyRecord(String status) throws Exception {
        UUID venueId = venue();
        UUID eventId = event();
        UUID bookingId = booking(venueId, eventId, status);
        // An unapplied request must not supersede the current event.
        jdbc.update("""
                INSERT INTO event_requests (request_id,event_id,request_type,status,expected_attendance)
                VALUES (?,?,'A','pending',999)
                """, UUID.randomUUID(), eventId);
        var tables = List.of("events", "venues", "venue_bookings", "event_requests");
        var before = tables.stream().map(this::snapshot).toList();
        entityManager.clear();
        mvc.perform(get("/api/venue-bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.venue.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.venue.venueCapacity").value(200))
                .andExpect(jsonPath("$.event.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.event.eventName").value("Workshop"))
                .andExpect(jsonPath("$.event.startDatetime").exists())
                .andExpect(jsonPath("$.event.endDatetime").exists())
                .andExpect(jsonPath("$.event.expectedAttendance").value(120))
                .andExpect(jsonPath("$.event.venueRequirements").value("Classroom seating"))
                .andExpect(jsonPath("$.event.accessibilityNeeds[0]").value("step_free_access"))
                .andExpect(jsonPath("$.event.equipmentRequirements").isEmpty());
        mvc.perform(get("/api/venues/" + venueId + "/bookings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].bookingId").value(bookingId.toString()));
        entityManager.flush();
        assertThat(tables.stream().map(this::snapshot).toList()).isEqualTo(before);
    }

    @Test
    void refetchReadsLatestSavedEventValues() throws Exception {
        UUID venueId = venue(); UUID eventId = event(); UUID bookingId = booking(venueId, eventId, "pending");
        mvc.perform(get("/api/venue-bookings/" + bookingId)).andExpect(jsonPath("$.event.expectedAttendance").value(120));
        jdbc.update("""
                UPDATE events SET expected_attendance=150, venue_requirements='Theatre seating',
                    start_datetime='2026-09-25T09:00:00+08:00',end_datetime='2026-09-25T12:00:00+08:00',
                    accessibility_needs=ARRAY['elevators']::accessibilities[],equipment_requirements='Projector'
                WHERE event_id=?
                """, eventId);
        entityManager.clear(); // A new HTTP request normally has a new persistence context.
        var result = mvc.perform(get("/api/venue-bookings/" + bookingId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.event.expectedAttendance").value(150))
                .andExpect(jsonPath("$.event.venueRequirements").value("Theatre seating"))
                .andExpect(jsonPath("$.event.accessibilityNeeds[0]").value("elevators"))
                .andExpect(jsonPath("$.event.equipmentRequirements").value("Projector"))
                .andReturn().getResponse().getContentAsString();
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result);
        assertThat(java.time.OffsetDateTime.parse(json.get("event").get("startDatetime").asString()).toInstant())
                .isEqualTo(java.time.OffsetDateTime.parse("2026-09-25T09:00:00+08:00").toInstant());
        mvc.perform(get("/api/venues/" + venueId + "/bookings"))
                .andExpect(jsonPath("$[0].event.expectedAttendance").value(150));
    }

    @Test
    void listsOnlyAssociatedBookingsAndReturnsEmptyForUnbookedVenue() throws Exception {
        UUID selected = venue(); UUID other = venue(); UUID eventId = event();
        UUID first = booking(selected, eventId, "pending");
        UUID second = booking(selected, eventId, "confirmed");
        booking(other, eventId, "pending");
        List<String> ordered = List.of(first.toString(), second.toString()).stream().sorted().toList();
        mvc.perform(get("/api/venues/" + selected + "/bookings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].bookingId").value(ordered.get(0)))
                .andExpect(jsonPath("$[1].bookingId").value(ordered.get(1)));
        mvc.perform(get("/api/venues/" + venue() + "/bookings"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void missingRecordsAndMalformedIdsUseNormalErrors() throws Exception {
        for (String path : List.of("/api/venue-bookings/" + UUID.randomUUID(),
                "/api/venues/" + UUID.randomUUID() + "/bookings")) {
            mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        }
        for (String path : List.of("/api/venue-bookings/not-a-uuid", "/api/venues/not-a-uuid/bookings")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
    }
}
