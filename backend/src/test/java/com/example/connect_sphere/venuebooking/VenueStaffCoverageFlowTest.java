package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.dto.UpdateBookingTimingRequest;
import com.example.connect_sphere.venuebooking.service.*;

@Tag("DEV16") @Tag("VS01") @Tag("VS-C1") @Tag("VS-C2") @Tag("VS-C3") @Tag("integration")
@SpringBootTest @AutoConfigureMockMvc @Transactional
class VenueStaffCoverageFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired VenueBookingService bookings;
    private UUID venue, event, booking, actor;
    private FlowSupport flow;

    @BeforeEach void fixture() {
        flow=new FlowSupport(mvc,users);
        actor=jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'",UUID.class);
        venue=venues.createVenue(new CreateVenueDto("Coverage hall "+UUID.randomUUID(),200,List.of(VenueLayout.theatre),
                "",null,List.of(),List.of(),java.util.stream.IntStream.rangeClosed(1,7)
                .mapToObj(day -> new VenueOperatingHourDto(day,LocalTime.MIDNIGHT,LocalTime.of(23,59))).toList())).venueId();
        event=UUID.randomUUID(); booking=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,
                venue_requirements,status,coordinator_id)
            VALUES (?,'Coverage workshop','Test','2027-04-01T02:00:00Z','2027-04-01T04:00:00Z',100,'Seating','pending',
                (SELECT user_id FROM users WHERE username='ec1'))
            """,event);
        jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,timing_note) VALUES (?,?,?,'pending','Crew access')",booking,venue,event);
    }
    private String savedBooking() {
        return jdbc.queryForObject("SELECT to_jsonb(b)::text FROM venue_bookings b WHERE booking_id=?",String.class,booking);
    }
    private UpdateBookingTimingRequest timing(BigDecimal setup,BigDecimal turnaround) {
        return new UpdateBookingTimingRequest(setup,turnaround,"Crew access",0,0);
    }

    @Test @DisplayName("DEV16 (happy): saved occupancy defaults can be read and changed without rewriting existing booking snapshots")
    void occupancySettings() throws Exception {
        String before=savedBooking();
        mvc.perform(get("/api/venues/"+venue+"/occupancy-settings").with(flow.as("vs1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupMinutes").value(0));
        mvc.perform(put("/api/venues/"+venue+"/occupancy-settings").with(flow.as("vs1"))
                .contentType("application/json").content("{\"setupMinutes\":45,\"turnaroundMinutes\":60}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupMinutes").value(45)).andExpect(jsonPath("$.turnaroundMinutes").value(60));
        mvc.perform(get("/api/venues/"+venue+"/occupancy-settings").with(flow.as("vs1")))
                .andExpect(jsonPath("$.setupMinutes").value(45)).andExpect(jsonPath("$.turnaroundMinutes").value(60));
        assertThat(savedBooking()).isEqualTo(before);
    }

    @Test @DisplayName("DEV16 (happy): active unavailable periods and retained release history are separate API reads")
    void unavailabilityReadsAndRelease() throws Exception {
        String before=savedBooking();
        UUID period=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO venue_unavailability(unavailability_id,venue_id,start_datetime,end_datetime,reason,created_by)
            VALUES (?,?,'2027-04-01T02:00:00Z','2027-04-01T04:00:00Z','Maintenance',?)
            """,period,venue,actor);
        String base="/api/venues/"+venue+"/unavailability";
        mvc.perform(get(base).with(flow.as("vs1"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].reason").value("Maintenance"));
        mvc.perform(patch(base+"/"+period+"/release").with(flow.as("vs1"))
                .contentType("application/json").content("{\"reason\":\"Repairs complete\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.releaseReason").value("Repairs complete"));
        mvc.perform(get(base).with(flow.as("vs1"))).andExpect(content().json("[]"));
        mvc.perform(get(base+"/history").with(flow.as("vs1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releasedAt").isNotEmpty()).andExpect(jsonPath("$[0].releaseReason").value("Repairs complete"));
        assertThat(savedBooking()).isEqualTo(before);
    }

    @Test @DisplayName("DEV16 (boundary): unavailable periods preserve millisecond timestamps from a normalised browser control")
    void fractionalUnavailablePeriod() throws Exception {
        mvc.perform(post("/api/venues/"+venue+"/unavailability").with(flow.as("vs1"))
                .contentType("application/json").content("""
                    {"startDateTime":"2027-04-02T10:00:30.123+08:00","endDateTime":"2027-04-02T11:00:45.987+08:00","reason":"Maintenance"}
                    """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("Maintenance"));
        var saved=jdbc.queryForMap("SELECT start_datetime,end_datetime FROM venue_unavailability WHERE venue_id=?",venue);
        assertThat(((java.sql.Timestamp)saved.get("start_datetime")).toInstant()).isEqualTo(java.time.Instant.parse("2027-04-02T02:00:30.123Z"));
        assertThat(((java.sql.Timestamp)saved.get("end_datetime")).toInstant()).isEqualTo(java.time.Instant.parse("2027-04-02T03:00:45.987Z"));
    }

    @Test @DisplayName("DEV16 (negative): absent timing requests and either missing duration leave the booking unchanged")
    void missingTimingDurations() {
        String before=savedBooking();
        for (var input : java.util.Arrays.asList(null,timing(null,BigDecimal.ZERO),timing(BigDecimal.ZERO,null))) {
            assertThatThrownBy(() -> bookings.updateTiming(booking,actor,input))
                    .isInstanceOf(InvalidVenueBookingException.class).hasMessage("Both setup and turnaround durations are required.");
            assertThat(savedBooking()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings={"rejected","cancelled","changed"})
    @DisplayName("DEV16 (negative): terminal booking timings cannot be edited and refusal preserves the saved record")
    void terminalBooking(String status) {
        jdbc.update("UPDATE venue_bookings SET status=?::venue_booking_status WHERE booking_id=?",status,booking);
        String before=savedBooking();
        assertThatThrownBy(() -> bookings.updateTiming(booking,actor,timing(BigDecimal.ZERO,BigDecimal.ZERO)))
                .isInstanceOf(VenueBookingStateException.class).hasMessageContaining("Only active pending or approved booking timings");
        assertThat(savedBooking()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","completed"})
    @DisplayName("DEV16 (negative): a final event prevents timing edits without rewriting its retained booking")
    void finalEvent(String status) {
        jdbc.update("UPDATE events SET status=?::event_status WHERE event_id=?",status,event);
        String before=savedBooking();
        assertThatThrownBy(() -> bookings.updateTiming(booking,actor,timing(BigDecimal.ZERO,BigDecimal.ZERO)))
                .isInstanceOf(VenueBookingStateException.class).hasMessageContaining("Only active pending or approved booking timings");
        assertThat(savedBooking()).isEqualTo(before);
    }

    @Test @DisplayName("DEV16 (boundary): saving identical timing values is a no-op with no new history or notifications")
    void unchangedTiming() {
        String before=savedBooking();
        var history=bookings.history(booking);
        Long notices=jdbc.queryForObject("SELECT count(*) FROM notification_outbox",Long.class);
        var result=bookings.updateTiming(booking,actor,timing(BigDecimal.ZERO,BigDecimal.ZERO));
        assertThat(result.bookingId()).isEqualTo(booking);
        assertThat(savedBooking()).isEqualTo(before);
        assertThat(bookings.history(booking)).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox",Long.class)).isEqualTo(notices);
    }
}
