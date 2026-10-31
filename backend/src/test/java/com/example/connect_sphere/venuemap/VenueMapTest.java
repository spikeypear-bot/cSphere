package com.example.connect_sphere.venuemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Tag("DEV16")
@Tag("AU04")
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles="VS")
class VenueMapTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueMapRepository map;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2027-04-01T00:00:00Z");

    private UUID venue() {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venues(venue_id,venue_name,venue_address,venue_capacity,operating_information,supported_layouts)
                VALUES (?,'Map room','Shared building',200,'Daily',ARRAY['theatre'])
                """,id);
        return id;
    }
    private UUID event(String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,status)
                VALUES (?,'Map workshop','Test','2027-05-01T01:00:00Z','2027-05-01T03:00:00Z',50,'Theatre',?::event_status)
                """,id,status);
        return id;
    }
    private UUID booking(UUID venue,UUID event,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status) VALUES (?,?,?,?::venue_booking_status)",id,venue,event,status);
        return id;
    }
    private String snapshot(String table) {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class);
    }

    @Test
    @DisplayName("DEV16 (happy): counts distinct events while preserving multi-venue and repeated booking identities")
    void distinctCountsAndAssociations() {
        UUID first=venue(), second=venue(), event=event("confirmed");
        UUID one=booking(first,event,"approved"), two=booking(first,event,"approved");
        booking(second,event,"approved");
        booking(first,event("pending"),"pending");
        var counts=map.activity(NOW,null,null).stream().filter(a->a.venueId().equals(first)).findFirst().orElseThrow();
        assertThat(counts.eventCount()).isEqualTo(1);
        assertThat(counts.pendingBookingCount()).isEqualTo(1);
        assertThat(counts.matchingBookingCount()).isEqualTo(3);
        assertThat(map.bookings(first,NOW,null,null,false)).filteredOn(b->b.eventId().equals(event))
                .extracting(VenueMapDto.Booking::bookingId).containsExactlyInAnyOrder(one,two);
        assertThat(map.bookings(second,NOW,null,null,false)).extracting(VenueMapDto.Booking::eventId).containsExactly(event);
    }

    @Test
    @DisplayName("DEV16 (negative): rejected cancelled replaced bookings and cancelled events are absent, attention stays distinct")
    void excludesInactiveRecords() {
        UUID venue=venue();
        for(String status:List.of("rejected","cancelled","changed")) booking(venue,event("confirmed"),status);
        booking(venue,event("cancelled"),"approved");
        UUID attention=booking(venue,event("pending"),"attention");
        assertThat(map.bookings(venue,NOW,null,null,false)).extracting(VenueMapDto.Booking::bookingId).containsExactly(attention);
        var count=map.activity(NOW,null,null).stream().filter(a->a.venueId().equals(venue)).findFirst().orElseThrow();
        assertThat(count.eventCount()).isZero();
        assertThat(count.pendingBookingCount()).isZero();
        assertThat(count.matchingBookingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DEV16 (boundary): Singapore date matching uses booking windows and excludes midnight-adjacent bookings")
    void dateBoundariesAndBookingTimes() throws Exception {
        UUID venue=venue(), event=event("confirmed");
        UUID endsAtStart=booking(venue,event,"approved"), startsAtEnd=booking(venue,event,"approved"), crosses=booking(venue,event,"approved");
        var day=LocalDate.now(ZoneId.of("Asia/Singapore")).plusDays(2);
        var start=day.atStartOfDay(ZoneId.of("Asia/Singapore")).toOffsetDateTime();
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id=?",start.minusHours(1),start,endsAtStart);
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id=?",start.plusDays(1),start.plusDays(1).plusHours(1),startsAtEnd);
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id=?",start.minusHours(1),start.plusHours(1),crosses);
        mvc.perform(get("/api/venue-staff/map/venues/"+venue+"/bookings").param("date",day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(crosses.toString()));
        assertThat(map.activity(start.minusDays(1),start,start.plusDays(1))).filteredOn(a->a.venueId().equals(venue))
                .extracting(VenueMapDto.Activity::matchingBookingCount).containsExactly(1);
    }

    @Test
    @DisplayName("DEV16 (boundary): past approved bookings are opt-in, completed events never appear as upcoming")
    void pastAndCompleted() {
        UUID venue=venue();
        UUID approved=booking(venue,event("completed"),"approved");
        booking(venue,event("completed"),"pending");
        UUID oldPending=booking(venue,event("pending"),"pending");
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id IN (?,?)",NOW.minusHours(2),NOW,approved,oldPending);
        booking(venue,event("completed"),"approved"); // Invalid future completed data must stay hidden too.
        assertThat(map.bookings(venue,NOW,null,null,false)).isEmpty();
        assertThat(map.bookings(venue,NOW,null,null,true)).extracting(VenueMapDto.Booking::bookingId).containsExactly(approved);
        assertThat(map.bookings(venue,NOW,null,null,true).getFirst().past()).isTrue();
    }

    @Test
    @DisplayName("DEV16 (boundary): an expired unswept hold is never returned as tentative and reads do not write")
    void holdExpiryAndReadOnly() throws Exception {
        UUID venue=venue(), event=event("pending"), booking=booking(venue,event,"pending");
        UUID user=jdbc.queryForObject("SELECT user_id FROM users LIMIT 1",UUID.class);
        UUID hold=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_booking_holds(hold_id,booking_id,reserved_start,reserved_end,expires_at,status,created_by,created_at)
                VALUES (?,?,'2027-05-01T01:00:00Z','2027-05-01T03:00:00Z',?,'active',?,?)
                """,hold,booking,NOW,user,NOW.minusHours(1));
        assertThat(map.bookings(venue,NOW.minusMinutes(1),null,null,false).getFirst().holdExpiresAt()).isEqualTo(NOW);
        assertThat(map.bookings(venue,NOW,null,null,false).getFirst().holdExpiresAt()).isNull();
        var tables=List.of("venues","events","venue_bookings","venue_booking_holds");
        var before=tables.stream().map(this::snapshot).toList();
        mvc.perform(get("/api/venue-staff/map/activity")).andExpect(status().isOk());
        mvc.perform(get("/api/venue-staff/map/venues/"+venue+"/bookings")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingNotes").doesNotExist()).andExpect(jsonPath("$[0].venueRequirements").doesNotExist());
        assertThat(tables.stream().map(this::snapshot).toList()).isEqualTo(before);
    }

    @Test
    @DisplayName("DEV16 (conflict): repeated reads reflect saved replacements and event cancellation immediately")
    void refreshedStatuses() throws Exception {
        UUID venue=venue(), event=event("confirmed"), booking=booking(venue,event,"approved");
        // Use relative future dates for the HTTP controller's real server time.
        jdbc.update("UPDATE events SET start_datetime=now()+interval '2 days',end_datetime=now()+interval '3 days' WHERE event_id=?",event);
        String path="/api/venue-staff/map/venues/"+venue+"/bookings";
        mvc.perform(get(path)).andExpect(jsonPath("$[0].status").value("approved"));
        jdbc.update("UPDATE venue_bookings SET status='changed' WHERE booking_id=?",booking);
        mvc.perform(get(path)).andExpect(content().json("[]"));
        booking(venue,event,"pending");
        mvc.perform(get(path)).andExpect(jsonPath("$.length()").value(1));
        jdbc.update("UPDATE events SET status='cancelled' WHERE event_id=?",event);
        mvc.perform(get(path)).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings={"EO","EC","ECL","ATTENDEE","TECHNICIAN","SAFETY_OFFICER"})
    @DisplayName("DEV16 AU04 (negative): every non-staff role is denied both map reads")
    void otherRoles(String role) throws Exception {
        UUID venue=venue();
        var before=snapshot("venue_bookings");
        for(String path:List.of("/api/venue-staff/map/activity","/api/venue-staff/map/venues/"+venue+"/bookings"))
            mvc.perform(get(path).with(user("other").roles(role))).andExpect(status().isForbidden());
        assertThat(snapshot("venue_bookings")).isEqualTo(before);
    }

    @Test
    @DisplayName("DEV16 AU04 (negative): signed-out access and invalid bearer tokens cannot obtain map data")
    void authentication() throws Exception {
        for(String path:List.of("/api/venue-staff/map/activity","/api/venue-staff/map/venues/"+UUID.randomUUID()+"/bookings")) {
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(anonymous()).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("DEV16 (failure): missing venues and malformed filters return actionable errors without writes")
    void errorsAndEmpty() throws Exception {
        UUID empty=venue();
        var before=snapshot("venue_bookings");
        mvc.perform(get("/api/venue-staff/map/venues/"+empty+"/bookings")).andExpect(content().json("[]"));
        mvc.perform(get("/api/venue-staff/map/venues/"+UUID.randomUUID()+"/bookings")).andExpect(status().isNotFound());
        mvc.perform(get("/api/venue-staff/map/venues/no/bookings")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/venue-staff/map/activity").param("date","not-a-date")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(get("/api/venue-staff/map/activity").param("date","2027-02-30")).andExpect(status().isBadRequest());
        assertThat(snapshot("venue_bookings")).isEqualTo(before);
    }

    @Test
    @DisplayName("DEV16 (boundary): the activity API applies Singapore midnight boundaries and returns saved venue counts")
    void activityDateAtHttpBoundary() throws Exception {
        UUID venue = venue(), event = event("confirmed");
        booking(venue, event, "approved");
        jdbc.update("UPDATE events SET start_datetime='2027-05-01T15:00:00Z',end_datetime='2027-05-01T16:00:00Z' WHERE event_id=?", event);
        var before = snapshot("venue_bookings");
        mvc.perform(get("/api/venue-staff/map/activity").param("date", "2027-05-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venueId == '" + venue + "')].eventCount").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$[?(@.venueId == '" + venue + "')].matchingBookingCount").value(org.hamcrest.Matchers.contains(1)));
        mvc.perform(get("/api/venue-staff/map/activity").param("date", "2027-05-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venueId == '" + venue + "')]").isEmpty());
        assertThat(snapshot("venue_bookings")).isEqualTo(before);
    }
}
