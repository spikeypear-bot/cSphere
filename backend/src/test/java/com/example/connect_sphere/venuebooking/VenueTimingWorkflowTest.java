package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;
import com.example.connect_sphere.venuebooking.dto.*;
import com.example.connect_sphere.venuebooking.service.*;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@Tag("VS-C2")
@Tag("VS-C4")
class VenueTimingWorkflowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired AvailabilityService availability;
    @Autowired VenueBookingService bookings;
    @Autowired VenueBookingRequestService requests;
    @Autowired TimingConflictService conflicts;
    final List<UUID> venueIds = new ArrayList<>(), eventIds = new ArrayList<>();
    UUID coordinator, staff;
    FlowSupport flow;

    @BeforeEach void before() {
        flow = new FlowSupport(mvc, users);
        coordinator = jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec1'", UUID.class);
        staff = jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'", UUID.class);
    }
    UUID venue(int setup, int turnaround) {
        UUID id = venues.createVenue(new CreateVenueDto("VSC2 " + UUID.randomUUID(), 200,
                List.of(VenueLayout.classroom), "Daily", null, List.of(), List.of(),
                java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new VenueOperatingHourDto(day,
                        java.time.LocalTime.MIDNIGHT, java.time.LocalTime.of(23,59))).toList(),
                BigDecimal.valueOf(setup), BigDecimal.valueOf(turnaround))).venueId();
        venueIds.add(id); return id;
    }
    OffsetDateTime at(String time) { return OffsetDateTime.parse("2030-04-01T" + time + ":00+08:00"); }
    UUID event(String start, String end) {
        UUID id = UUID.randomUUID(); eventIds.add(id);
        jdbc.update("""
                INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,
                    venue_requirements,accessibility_needs,status,coordinator_id)
                VALUES (?,'Timing workshop','Test',?,?,100,'Early access for stage setup','{}','pending',?)
                """, id, at(start), at(end), coordinator);
        return id;
    }
    UUID booking(UUID venue, String status, String start, String end, int setup, int turnaround) {
        UUID e = event(start, end), b = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,setup_duration_minutes,turnaround_duration_minutes)
                VALUES (?,?,?,?::venue_booking_status,?,?)
                """, b, venue, e, status, setup, turnaround);
        return b;
    }
    UpdateBookingTimingRequest edit(int setup, int turnaround, int previousSetup, int previousTurnaround) {
        return new UpdateBookingTimingRequest(BigDecimal.valueOf(setup), BigDecimal.valueOf(turnaround),
                "Adjusted crew access", previousSetup, previousTurnaround);
    }
    String snapshot(UUID booking) {
        return jdbc.queryForObject("SELECT to_jsonb(b)::text FROM venue_bookings b WHERE booking_id=?", String.class, booking);
    }
    int notificationCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type::text=? AND event_id IN (SELECT event_id FROM events WHERE event_name='Timing workshop')", Integer.class, type);
    }
    @AfterEach void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS vsc2_fail_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS vsc2_fail_notification()");
        for (UUID id : eventIds) {
            jdbc.update("DELETE FROM notifications WHERE event_id=?", id);
            jdbc.update("DELETE FROM venue_unavailability_bookings WHERE booking_id IN (SELECT booking_id FROM venue_bookings WHERE event_id=?)", id);
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?", id);
            jdbc.update("DELETE FROM events WHERE event_id=?", id);
        }
        for (UUID id : venueIds) {
            jdbc.update("DELETE FROM venue_unavailability WHERE venue_id=?", id);
            jdbc.update("DELETE FROM venue_operational_issues WHERE venue_id=?", id);
            jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?", id);
            jdbc.update("DELETE FROM venues WHERE venue_id=?", id);
        }
    }


    SubmitVenueBookingRequest timedRequest(UUID venue, String start, String end, int setup, int turnaround) {
        return new SubmitVenueBookingRequest(venue, null, null, null, at(start), at(end),
                BigDecimal.valueOf(setup), BigDecimal.valueOf(turnaround), "Independent crew access");
    }

    @Test void multipleBookingWindowsDriveSubmissionApprovalCalendarAndStaffEdits() {
        UUID first = venue(30,15), second = venue(15,30), e = event("08:00","18:00");
        var results = requests.submitBatch(coordinator, e, new SubmitVenueBookingRequest.Batch(List.of(
                timedRequest(first,"10:00","12:00",30,15), timedRequest(second,"14:00","16:00",15,30))));
        assertThat(results).hasSize(2);
        assertThat(results.get(0).timing().effectiveStart()).isEqualTo(at("09:30"));
        assertThat(results.get(1).timing().effectiveEnd()).isEqualTo(at("16:30"));
        for (var result : results) assertThat(bookings.approve(result.bookingId()).status().name()).isEqualTo("approved");
        var detail = bookings.get(results.get(1).bookingId());
        assertThat(detail.event().startDatetime()).isEqualTo(at("08:00"));
        assertThat(detail.timing().startDatetime()).isEqualTo(at("14:00"));
        assertThat(availability.schedule(second,at("13:45"),at("14:00")).bookings()).hasSize(1);
        assertThat(availability.schedule(second,at("08:00"),at("09:00")).bookings()).isEmpty();
        var edited = bookings.updateTiming(results.get(1).bookingId(), staff, edit(45,45,15,30));
        assertThat(edited.timing().effectiveStart()).isEqualTo(at("13:15"));
        assertThat(edited.timing().effectiveEnd()).isEqualTo(at("16:45"));
        assertThat(bookings.get(results.get(0).bookingId()).timing().setupDurationMinutes()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT start_datetime FROM events WHERE event_id=?",OffsetDateTime.class,e)).isEqualTo(at("08:00"));
    }

    @Test void availabilityRechecksTheRequestedWindowAndBuffersInsteadOfTheEventWindow() throws Exception {
        UUID v = venue(0,0), e = event("15:00","17:00");
        UUID existing = booking(v,"approved","08:00","18:00",0,30);
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id=?",at("10:00"),at("11:00"),existing);
        mvc.perform(get("/api/events/"+e+"/venue-options").with(flow.as("ec1"))
                .param("startDatetime",at("11:45").toString()).param("endDatetime",at("13:00").toString())
                .param("setupDurationMinutes","30").param("turnaroundDurationMinutes","0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venue.venueId=='"+v+"')].verdict").value(org.hamcrest.Matchers.contains("blocked")));
        assertThatThrownBy(() -> requests.submit(coordinator,e,timedRequest(v,"11:45","13:00",30,0)))
                .isInstanceOf(InvalidVenueBookingException.class);
        var touching = requests.submit(coordinator,e,timedRequest(v,"12:00","13:00",30,0));
        assertThat(bookings.approve(touching.bookingId()).status().name()).isEqualTo("approved");
        assertThatThrownBy(() -> bookings.updateTiming(touching.bookingId(),staff,edit(31,0,30,0)))
                .isInstanceOf(VenueBookingConflictException.class);
    }

    @Test void batchRejectsBufferOnlyOverlapAndRollsBackEarlierRequests() {
        UUID v=venue(0,0), e=event("08:00","18:00");
        assertThatThrownBy(() -> requests.submitBatch(coordinator,e,new SubmitVenueBookingRequest.Batch(List.of(
                timedRequest(v,"10:00","11:00",0,30),timedRequest(v,"11:45","13:00",30,0)))))
                .isInstanceOf(InvalidVenueBookingException.class).hasMessageContaining("occupied times");
        UUID second=venue(0,0);
        booking(second,"approved","14:00","15:00",0,30);
        assertThatThrownBy(() -> requests.submitBatch(coordinator,e,new SubmitVenueBookingRequest.Batch(List.of(
                timedRequest(v,"10:00","11:00",0,0),timedRequest(second,"15:45","17:00",30,0)))))
                .isInstanceOf(InvalidVenueBookingException.class);
        assertThat(requests.bookingsForEvent(coordinator,e)).isEmpty();
    }

    @Test void bookingWindowControlsOpeningHoursAndUnavailability() {
        UUID v=venue(0,0), e=event("08:00","18:00");
        jdbc.update("UPDATE venue_operating_hours SET open_time='09:30',close_time='12:30' WHERE venue_id=?",v);
        assertThatThrownBy(() -> requests.submit(coordinator,e,timedRequest(v,"10:00","12:00",31,30)))
                .isInstanceOf(InvalidVenueBookingException.class);
        var result=requests.submit(coordinator,e,timedRequest(v,"10:00","12:00",30,30));
        assertThat(bookings.approve(result.bookingId()).status().name()).isEqualTo("approved");
        var outside = availability.preview(v,new Request(at("08:00"),at("09:00"),"Outside booking",null,null));
        assertThat(outside.affectedBookings()).isEmpty();
        var buffer = availability.preview(v,new Request(at("09:30"),at("09:45"),"Setup blocked",null,null));
        assertThat(buffer.affectedBookings()).hasSize(1);
    }

    @Test void operationalIssueDuringSetupBlocksApprovalAndTimingExpansion() {
        UUID v=venue(0,0), e=event("08:00","18:00");
        var result=requests.submit(coordinator,e,timedRequest(v,"10:00","12:00",30,15));
        jdbc.update("INSERT INTO venue_operational_issues(issue_id,venue_id,description,affected_from,affected_until,created_by) VALUES (?,?,'Setup access blocked',?,?,?)",
                UUID.randomUUID(),v,at("09:15"),at("09:45"),staff);
        assertThatThrownBy(() -> bookings.approve(result.bookingId())).isInstanceOf(VenueBookingStateException.class)
                .hasMessageContaining("operational issue");
        bookings.updateTiming(result.bookingId(),staff,edit(15,15,30,15));
        assertThat(bookings.approve(result.bookingId()).status().name()).isEqualTo("approved");
        assertThatThrownBy(() -> bookings.updateTiming(result.bookingId(),staff,edit(30,15,15,15)))
                .isInstanceOf(VenueBookingStateException.class);
    }

    @Test void incomingLifecycleNotificationsRemainValidAlongsideTimingNotifications() {
        UUID v=venue(0,0), b=booking(v,"approved","10:00","12:00",0,0);
        UUID e=jdbc.queryForObject("SELECT event_id FROM venue_bookings WHERE booking_id=?",UUID.class,b);
        for (String type : List.of("event_confirmed","event_returned_to_planning","event_safety_approved")) {
            jdbc.update("INSERT INTO notifications(notification_id,recipient_user_id,event_id,type,event_name) VALUES (?,?,?,?::notification_type,'Timing workshop')",
                    UUID.randomUUID(),coordinator,e,type);
            assertThat(notificationCount(type)).isEqualTo(1);
        }
        bookings.updateTiming(b,staff,edit(15,15,0,0));
        assertThat(notificationCount("venue_timing_changed")).isEqualTo(1);
    }

    @Test void defaultsAreVisibleEditableAndDoNotRewriteExistingBookings() throws Exception {
        UUID v = venue(30, 45), b = booking(v, "approved", "10:00", "12:00", 30, 45);
        String before = snapshot(b);
        mvc.perform(get("/api/venues/" + v).with(flow.as("ec1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.setupMinutes").value(30)).andExpect(jsonPath("$.turnaroundMinutes").value(45));
        mvc.perform(put("/api/venues/" + v).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setupMinutes\":60,\"turnaroundMinutes\":15}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupMinutes").value(60));
        assertThat(snapshot(b)).isEqualTo(before);
        mvc.perform(put("/api/venues/" + v).with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setupMinutes\":0}")).andExpect(status().isForbidden());
        assertThat(availability.settings(v)).isEqualTo(new Settings(60,15));
    }

    @Test void approvalConflictReturnsSavedDurationsAndBothTimeRanges() throws Exception {
        UUID v = venue(120,120), existing = booking(v,"approved","10:00","12:00",30,45);
        UUID requested = booking(v,"pending","11:00","13:00",0,0);
        var response = mvc.perform(patch("/api/venue-bookings/" + requested + "/approve").with(flow.as("vs1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].bookingId").value(existing.toString()))
                .andExpect(jsonPath("$.conflicts[0].setupDurationMinutes").value(30))
                .andExpect(jsonPath("$.conflicts[0].turnaroundDurationMinutes").value(45))
                .andReturn().getResponse().getContentAsString();
        var conflict = flow.read(response).get("conflicts").get(0);
        assertThat(OffsetDateTime.parse(conflict.get("startDatetime").asString())).isEqualTo(at("10:00"));
        assertThat(OffsetDateTime.parse(conflict.get("endDatetime").asString())).isEqualTo(at("12:00"));
        assertThat(OffsetDateTime.parse(conflict.get("effectiveStart").asString())).isEqualTo(at("09:30"));
        assertThat(OffsetDateTime.parse(conflict.get("effectiveEnd").asString())).isEqualTo(at("12:45"));
    }

    @Test void fractionalNegativeAndOutOfRangeDurationsCannotBeSaved() throws Exception {
        UUID v = venue(0, 0), b = booking(v, "pending", "10:00", "12:00", 0, 0);
        String before = snapshot(b);
        for (String invalid : List.of("-1", "0.5", "10081", "\"oops\"")) {
            mvc.perform(put("/api/venues/" + v).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"setupMinutes\":" + invalid + "}")).andExpect(status().is4xxClientError());
            mvc.perform(put("/api/venue-bookings/" + b + "/timing").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"setupDurationMinutes\":" + invalid + ",\"turnaroundDurationMinutes\":0,\"timingNote\":\"Test\",\"previousSetupDurationMinutes\":0,\"previousTurnaroundDurationMinutes\":0}"))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(snapshot(b)).isEqualTo(before);
    }

    @Test void openingHoursCoverTheEntireOccupiedPeriodAcrossAllVenueActions() throws Exception {
        UUID v = venue(30,45), e = event("09:00","12:00");
        jdbc.update("UPDATE venue_operating_hours SET open_time='09:00',close_time='13:00' WHERE venue_id=?", v);
        assertThat(requests.venueOptions(coordinator,e).stream().filter(o -> o.venue().venueId().equals(v))
                .findFirst().orElseThrow().verdict()).isEqualTo("blocked");
        assertThatThrownBy(() -> requests.submit(coordinator,e,new SubmitVenueBookingRequest(v,null,null)))
                .isInstanceOf(InvalidVenueBookingException.class);
        UUID b = booking(v,"pending","09:00","12:00",30,45);
        assertThatThrownBy(() -> bookings.approve(b)).isInstanceOf(VenueBookingStateException.class);
        bookings.updateTiming(b,staff,edit(0,60,30,45)); // Both occupied boundaries exactly match opening hours.
        assertThat(bookings.approve(b).status().name()).isEqualTo("approved");
        assertThatThrownBy(() -> bookings.updateTiming(b,staff,edit(1,60,0,60))).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(() -> bookings.updateTiming(b,staff,edit(0,61,0,60))).isInstanceOf(VenueBookingStateException.class);
        mvc.perform(get("/api/venues/search").with(flow.as("ec1")).param("startDatetime",at("09:00").plusDays(1).toString())
                .param("endDatetime",at("12:00").plusDays(1).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].venueId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(v.toString()))));
    }

    @Test void coordinatorOverridesRequireReasonAndPreserveChosenValues() throws Exception {
        UUID v = venue(30, 45), e = event("10:00", "12:00");
        var input = new SubmitVenueBookingRequest(v, "Organiser needs stage setup", null, null, BigDecimal.valueOf(60), BigDecimal.ZERO, "Crew starts early");
        assertThatThrownBy(() -> requests.submit(coordinator, e, new SubmitVenueBookingRequest(v, null, null, null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, null))).isInstanceOf(InvalidVenueBookingException.class);
        var result = requests.submit(coordinator, e, input);
        assertThat(result.timing().setupDurationMinutes()).isEqualTo(60);
        assertThat(result.timing().turnaroundDurationMinutes()).isZero();
        assertThat(result.timing().effectiveStart()).isEqualTo(at("09:00"));
        assertThat(result.timing().timingNote()).isEqualTo("Crew starts early");
        assertThat(bookings.get(result.bookingId()).timing().setupDurationMinutes()).isEqualTo(60);
        mvc.perform(get("/api/events/" + e + "/venue-bookings").with(flow.as("ec2"))).andExpect(status().isForbidden());
    }

    @Test void overriddenOccupancyDrivesOptionsSubmissionAndSearch() throws Exception {
        UUID v = venue(0, 0), e = event("11:00", "12:00");
        booking(v, "approved", "09:00", "10:00", 0, 45);
        assertThat(requests.venueOptions(coordinator, e, BigDecimal.valueOf(30), BigDecimal.ZERO).stream()
                .filter(o -> o.venue().venueId().equals(v)).findFirst().orElseThrow().verdict()).isEqualTo("blocked");
        assertThat(requests.venueOptions(coordinator, e, BigDecimal.valueOf(15), BigDecimal.ZERO).stream()
                .filter(o -> o.venue().venueId().equals(v)).findFirst().orElseThrow().verdict()).isEqualTo("suitable");
        assertThatThrownBy(() -> requests.submit(coordinator, e, new SubmitVenueBookingRequest(v, null, null, null,
                BigDecimal.valueOf(30), BigDecimal.ZERO, "Crew access"))).isInstanceOf(InvalidVenueBookingException.class);
        mvc.perform(get("/api/venues/search").with(flow.as("ec1")).param("startDatetime",at("10:30").toString())
                .param("endDatetime",at("11:00").toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].venueId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(v.toString()))));
    }

    @Test void safeEditNotifiesCoordinatorAndRejectsStaleOrUnauthorisedWrites() throws Exception {
        UUID v = venue(0,0), b = booking(v,"approved","10:00","12:00",0,0);
        var saved = bookings.updateTiming(b, staff, edit(30,45,0,0));
        assertThat(saved.timing().effectiveStart()).isEqualTo(at("09:30"));
        assertThat(saved.timing().effectiveEnd()).isEqualTo(at("12:45"));
        assertThat(notificationCount("venue_timing_changed")).isEqualTo(1);
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM venue_bookings WHERE booking_id=?", UUID.class, b);
        mvc.perform(get("/api/events/" + eventId + "/venue-bookings").with(flow.as("so1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].timing.setupDurationMinutes").value(30))
                .andExpect(jsonPath("$[0].timing.turnaroundDurationMinutes").value(45));
        mvc.perform(get("/api/events/" + eventId + "/equipment-requests").with(flow.as("so1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/events/" + eventId + "/readiness").with(flow.as("so1")))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> bookings.updateTiming(b, staff, edit(10,10,0,0))).isInstanceOf(VenueBookingStateException.class);
        for (String role : List.of("ec1","eo1","ts1","ecl1","so1","att1"))
            mvc.perform(put("/api/venue-bookings/"+b+"/timing").with(flow.as(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VS-C4 AC4, AC6, AC10 (conflict): GET returns current unique pairs without writes and drops resolved pairs")
    void conflictRetrievalIsFreshReadOnlyAndIncludesBookingAndEventWindows() throws Exception {
        UUID v=venue(0,0), a=booking(v,"approved","09:00","10:00",0,45), b=booking(v,"approved","10:30","12:00",0,0);
        jdbc.update("UPDATE venue_bookings SET start_datetime=?, end_datetime=? WHERE booking_id=?", at("09:05"), at("10:00"), a);
        String beforeA=snapshot(a), beforeB=snapshot(b);
        int notifications=notificationCount("venue_timing_conflict");
        for (int i=0; i<2; i++) mvc.perform(get("/api/venues/"+v+"/timing-conflicts").with(flow.as("vs1")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].first.venueId").value(v.toString()))
            .andExpect(jsonPath("$[0].first.eventStartDateTime").exists())
            .andExpect(jsonPath("$[0].first.setupDurationMinutes").exists())
            .andExpect(jsonPath("$[0].overlapStart").exists()).andExpect(jsonPath("$[0].overlapEnd").exists());
        assertThat(snapshot(a)).isEqualTo(beforeA); assertThat(snapshot(b)).isEqualTo(beforeB);
        assertThat(notificationCount("venue_timing_conflict")).isEqualTo(notifications);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_timing_conflicts WHERE first_booking_id IN (?,?)", Integer.class,a,b)).isZero();
        jdbc.update("UPDATE venue_bookings SET turnaround_duration_minutes=30 WHERE booking_id=?",a);
        mvc.perform(get("/api/venues/"+v+"/timing-conflicts").with(flow.as("vs1")))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        // Independent booking windows and cancellations are reflected on the very next GET.
        jdbc.update("UPDATE venue_bookings SET start_datetime=?,end_datetime=? WHERE booking_id=?",at("10:15"),at("12:00"),b);
        assertThat(conflicts.list(v)).hasSize(1);
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1"))
            .param("start",at("09:00").toString()).param("end",at("09:30").toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bookings.length()").value(1))
            .andExpect(jsonPath("$.activeConflicts.length()").value(1));
        jdbc.update("UPDATE venue_bookings SET status='cancelled' WHERE booking_id=?",b);
        assertThat(conflicts.list(v)).isEmpty();
        assertThat(bookings.get(a).status().name()).isEqualTo("approved");
    }

    @Test
    @DisplayName("VS-C4 AC4, AC6 (negative): exclude released, other-venue and inactive-event bookings; enforce role and missing IDs")
    void conflictRetrievalRespectsExistingScope() throws Exception {
        UUID v=venue(0,0), other=venue(0,0);
        booking(v,"approved","09:00","12:00",0,0);
        for (String status: List.of("pending","rejected","cancelled","changed")) booking(v,status,"10:00","11:00",60,60);
        booking(other,"approved","10:00","11:00",60,60);
        UUID inactive=booking(v,"approved","10:00","11:00",60,60);
        for (String status: List.of("completed","cancelled")) {
            jdbc.update("UPDATE events SET status=?::event_status WHERE event_id=(SELECT event_id FROM venue_bookings WHERE booking_id=?)",status,inactive);
            assertThat(conflicts.list(v)).isEmpty();
        }
        mvc.perform(get("/api/venues/"+UUID.randomUUID()+"/timing-conflicts").with(flow.as("vs1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/venues/invalid/timing-conflicts").with(flow.as("vs1"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/venues/"+v+"/timing-conflicts").with(flow.as("ec1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venues/"+v+"/timing-conflicts")).andExpect(status().isUnauthorized());
    }

    @Test void legacyOverlapsAreReportedOnceWithoutCancellingAndCanBeReduced() {
        UUID v=venue(0,0), a=booking(v,"approved","09:00","10:00",0,45), b=booking(v,"approved","10:30","12:00",0,0);
        String beforeA=snapshot(a), beforeB=snapshot(b);
        assertThat(conflicts.scan(v)).hasSize(1);
        assertThat(conflicts.scan(v)).hasSize(1);
        assertThat(notificationCount("venue_timing_conflict")).isEqualTo(2);
        assertThat(snapshot(a)).isEqualTo(beforeA); assertThat(snapshot(b)).isEqualTo(beforeB);
        assertThat(bookings.get(b).timing().conflicts()).hasSize(1);
        bookings.updateTiming(a,staff,edit(0,30,0,45));
        assertThat(conflicts.scan(v)).isEmpty();
        assertThat(bookings.get(a).status().name()).isEqualTo("approved");
    }

    @Test void conflictingEditsAndNotificationFailuresRollBackCompletely() {
        UUID v=venue(0,0), a=booking(v,"approved","09:00","10:00",0,0);
        booking(v,"approved","11:00","12:00",0,0);
        String before=snapshot(a);
        assertThatThrownBy(() -> bookings.updateTiming(a,staff,edit(0,61,0,0))).isInstanceOf(VenueBookingConflictException.class);
        assertThat(snapshot(a)).isEqualTo(before);
        jdbc.execute("CREATE FUNCTION vsc2_fail_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Test notification failure'; END $$");
        jdbc.execute("CREATE TRIGGER vsc2_fail_notification BEFORE INSERT ON notifications FOR EACH ROW WHEN (NEW.venue_booking_id='"+a+"'::uuid) EXECUTE FUNCTION vsc2_fail_notification()");
        assertThatThrownBy(() -> bookings.updateTiming(a,staff,edit(10,30,0,0))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot(a)).isEqualTo(before);
    }

    @Test void timingEditAndApprovalCannotRaceIntoAnOverlap() throws Exception {
        UUID v=venue(0,0), a=booking(v,"approved","09:00","10:00",0,0), b=booking(v,"pending","11:00","12:00",0,0);
        CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var edit=pool.submit(() -> { start.await(); try { bookings.updateTiming(a,staff,edit(0,90,0,0)); return true; }
                catch (VenueBookingStateException ex) { return false; } });
            var approve=pool.submit(() -> { start.await(); try { bookings.approve(b); return true; }
                catch (VenueBookingStateException ex) { return false; } });
            start.countDown();
            assertThat(edit.get(15,TimeUnit.SECONDS)).isNotEqualTo(approve.get(15,TimeUnit.SECONDS));
            assertThat(conflicts.list(v)).isEmpty();
        }
    }
}
