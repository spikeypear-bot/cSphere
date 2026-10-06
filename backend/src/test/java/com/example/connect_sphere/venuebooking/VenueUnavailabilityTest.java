package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.service.*;

/** Committed, uniquely identified fixtures exercise transactions and locking on PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class VenueUnavailabilityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired AvailabilityService availability;
    @Autowired VenueBookingService bookings;
    @Autowired VenueBookingRequestService requests;
    List<UUID> venueIds = new ArrayList<>();
    List<UUID> eventIds = new ArrayList<>();
    UUID actor, coordinator;
    FlowSupport flow;
    @BeforeEach void before() {
        flow = new FlowSupport(mvc, users);
        actor = jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'", UUID.class);
        coordinator = jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec1'", UUID.class);
    }
    UUID venue() {
        UUID id = venues.createVenue(new CreateVenueDto("VS01 " + UUID.randomUUID(), 200,
                List.of(VenueLayout.classroom), "Daily", null, List.of(), List.of(),
                java.util.stream.IntStream.rangeClosed(1,7).mapToObj(day ->
                        new com.example.connect_sphere.venue.dto.VenueOperatingHourDto(day,
                                java.time.LocalTime.MIDNIGHT, java.time.LocalTime.of(23,59))).toList())).venueId();
        venueIds.add(id); return id;
    }
    UUID booking(UUID venue, String status) {
        UUID event = UUID.randomUUID(); eventIds.add(event);
        jdbc.update("""
                INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,
                venue_requirements,accessibility_needs,status,coordinator_id)
                VALUES (?,'VS01 Workshop','Test','2027-04-01T10:00:00+08:00','2027-04-01T12:00:00+08:00',
                100,'Seating','{}','pending',?)
                """, event, coordinator);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,booking_notes) VALUES (?,?,?,?::venue_booking_status,'Preserve me')",
                id, venue, event, status);
        return id;
    }
    UUID event(UUID booking) { return jdbc.queryForObject("SELECT event_id FROM venue_bookings WHERE booking_id=?", UUID.class, booking); }
    String statusOf(UUID booking) { return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?", String.class, booking); }
    Request period(String start, String end) {
        return new Request(OffsetDateTime.parse("2027-04-01T" + start + ":00+08:00"),
                OffsetDateTime.parse("2027-04-01T" + end + ":00+08:00"), "Maintenance", null, null);
    }
    Period record(UUID venue, Request r) {
        Preview p = availability.preview(venue, r);
        return availability.create(venue, actor, new Request(r.startDateTime(), r.endDateTime(), r.reason(),
                p.affectedBookings().stream().map(Booking::bookingId).toList(), p.previewToken()));
    }
    @AfterEach void clean() {
        for (UUID id : eventIds) {
            jdbc.update("DELETE FROM notifications WHERE event_id=?", id);
            jdbc.update("DELETE FROM venue_unavailability_bookings WHERE booking_id IN (SELECT booking_id FROM venue_bookings WHERE event_id=?)", id);
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?", id);
            jdbc.update("DELETE FROM events WHERE event_id=?", id);
        }
        for (UUID id : venueIds) {
            jdbc.update("DELETE FROM venue_unavailability WHERE venue_id=?", id);
            jdbc.update("DELETE FROM venues WHERE venue_id=?", id);
        }
    }
    @Test void calendarIncludesHistoryBuffersAndOverlapsWithoutChangingOperationalReads() throws Exception {
        UUID v=venue();
        availability.updateSettings(v, new Settings(30,45));
        UUID approved=booking(v,"approved"), pending=booking(v,"pending");
        booking(v,"cancelled"); booking(v,"changed"); booking(v,"rejected");
        UUID cancelledEvent=booking(v,"approved");
        jdbc.update("UPDATE events SET status='cancelled' WHERE event_id=?", event(cancelledEvent));
        jdbc.update("UPDATE events SET status='completed' WHERE event_id=?", event(approved));
        booking(venue(),"approved");
        record(v,period("09:00","11:00"));
        var start=OffsetDateTime.parse("2027-04-01T09:30:00+08:00");
        var end=OffsetDateTime.parse("2027-04-01T09:45:00+08:00");
        var calendar=availability.schedule(v,start,end);
        assertThat(calendar.bookings()).extracting(Booking::bookingId).containsExactlyInAnyOrder(approved,pending);
        assertThat(calendar.unavailablePeriods()).hasSize(1);
        assertThat(availability.schedule(v).bookings()).extracting(Booking::bookingId).containsExactly(pending);
        assertThat(availability.schedule(v,OffsetDateTime.parse("2027-04-01T12:45:00+08:00"),
                OffsetDateTime.parse("2027-04-01T13:00:00+08:00")).bookings()).isEmpty();
        assertThat(availability.schedule(v,OffsetDateTime.parse("2027-04-01T08:00:00+08:00"),start).bookings()).isEmpty();
        assertThat(availability.schedule(v,OffsetDateTime.parse("2027-04-01T11:00:00+08:00"),end.plusHours(3)).unavailablePeriods()).isEmpty();
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1"))
                .param("start",start.toString()).param("end",end.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.bookings.length()").value(2));
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("ec1"))
                .param("start",start.toString()).param("end",end.toString())).andExpect(status().isForbidden());
    }

    @Test void calendarRejectsInvalidRangesAndReturnsEmptyPeriods() throws Exception {
        UUID v=venue();
        String start="2027-04-01T00:00:00+08:00", end="2027-05-01T00:00:00+08:00";
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1")).param("start",start).param("end",end))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookings").isEmpty()).andExpect(jsonPath("$.unavailablePeriods").isEmpty());
        for (String invalidEnd : List.of(start,"2027-03-01T00:00:00+08:00","2028-05-01T00:00:00+08:00")) {
            mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1")).param("start",start).param("end",invalidEnd))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1")).param("start",start))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1")).param("start","invalid").param("end",end))
                .andExpect(status().isBadRequest());
    }

    @Test void overlappingPeriodsAreRejectedByPreviewAndCreateWithoutSideEffects() throws Exception {
        UUID v=venue(); record(v,period("10:00","12:00"));
        booking(v,"approved");
        for (String[] times : List.of(new String[]{"10:30","11:00"}, new String[]{"09:00","13:00"},
                new String[]{"09:00","11:00"}, new String[]{"11:00","13:00"},
                new String[]{"10:00","12:00"}, new String[]{"10:00","11:00"}, new String[]{"11:00","12:00"})) {
            Request r=period(times[0],times[1]);
            for (String endpoint : List.of("/unavailability", "/unavailability/preview")) {
                mvc.perform(post("/api/venues/"+v+endpoint).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDateTime\":\""+r.startDateTime()+"\",\"endDateTime\":\""+r.endDateTime()+"\",\"reason\":\"Maintenance\"}"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overlaps an existing unavailable period")));
            }
        }
        assertThat(availability.list(v)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_unavailability_bookings WHERE unavailability_id IN (SELECT unavailability_id FROM venue_unavailability WHERE venue_id=?)",Integer.class,v)).isZero();
    }
    @Test void separatedAndTouchingPeriodsAreAllowedAndVenuesAreIndependent() {
        UUID v=venue(); record(v,period("10:00","12:00"));
        record(v,period("09:00","10:00")); record(v,period("12:00","13:00")); record(v,period("15:00","16:00"));
        record(venue(),period("10:30","11:00"));
        assertThat(availability.list(v)).hasSize(4);
    }
    @Test void multiDayOverlapAndEquivalentOffsetsAreCheckedAgainAfterPreview() {
        UUID v=venue();
        Request inner=new Request(OffsetDateTime.parse("2027-10-08T10:00:00+08:00"),
                OffsetDateTime.parse("2027-10-10T10:00:00+08:00"),"Maintenance",null,null);
        Preview p=availability.preview(v,inner);
        record(v,new Request(OffsetDateTime.parse("2027-10-07T10:00:00+08:00"),
                OffsetDateTime.parse("2027-10-20T14:00:00+08:00"),"Maintenance",null,null));
        assertThatThrownBy(() -> availability.create(v,actor,new Request(inner.startDateTime(),inner.endDateTime(),
                inner.reason(),List.of(),p.previewToken()))).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(() -> availability.preview(v,new Request(OffsetDateTime.parse("2027-10-20T05:00:00Z"),
                OffsetDateTime.parse("2027-10-20T07:00:00Z"),"Maintenance",null,null)))
                .isInstanceOf(VenueBookingStateException.class);
        record(v,new Request(OffsetDateTime.parse("2027-10-20T17:00:00+08:00"),
                OffsetDateTime.parse("2027-10-30T10:00:00+08:00"),"Maintenance",null,null));
        assertThat(availability.list(v)).hasSize(2);
    }
    @Test void simultaneousOverlappingRecordsAllowOnlyOneSave() throws Exception {
        UUID v=venue(); CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> { start.await(); try {
                availability.create(v,actor,period("10:00","12:00")); return true;
            } catch (VenueBookingStateException ex) { return false; } });
            var second=pool.submit(() -> { start.await(); try {
                availability.create(v,actor,period("11:00","13:00")); return true;
            } catch (VenueBookingStateException ex) { return false; } });
            start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isNotEqualTo(second.get(15,TimeUnit.SECONDS));
            assertThat(availability.list(v)).hasSize(1);
        }
    }
    @Test void buffersBoundariesStatusesAndVenueScoping() {
        UUID v = venue(); availability.updateSettings(v, new Settings(30,45));
        UUID approved = booking(v,"approved"), pending = booking(v,"pending");
        booking(v,"cancelled"); booking(v,"rejected"); booking(v,"changed"); booking(venue(),"approved");
        assertThat(availability.preview(v, period("09:00","09:30")).affectedBookings()).isEmpty();
        assertThat(availability.preview(v, period("09:20","09:31")).affectedBookings()).extracting(Booking::bookingId)
                .containsExactlyInAnyOrder(approved,pending);
        assertThat(availability.preview(v, period("12:44","13:00")).affectedBookings()).hasSize(2);
        assertThat(availability.preview(v, period("12:45","13:00")).affectedBookings()).isEmpty();
    }
    @Test void confirmationPreservesBookingsAndNotifiesAssignedCoordinator() {
        UUID v = venue(), b = booking(v,"approved");
        String original = jdbc.queryForObject("SELECT to_jsonb(b)::text FROM venue_bookings b WHERE booking_id=?",String.class,b);
        assertThatThrownBy(() -> availability.create(v,actor,period("10:00","11:00"))).isInstanceOf(VenueBookingStateException.class);
        assertThat(availability.list(v)).isEmpty();
        record(v,period("10:00","11:00"));
        assertThat(jdbc.queryForObject("SELECT to_jsonb(b)::text FROM venue_bookings b WHERE booking_id=?",String.class,b)).isEqualTo(original);
        assertThat(availability.affected(b)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=? AND recipient_user_id=? AND type='venue_unavailable'",Integer.class,b,coordinator)).isEqualTo(1);
    }
    @Test void stalePreviewRequiresAnotherConfirmation() {
        UUID v = venue(); booking(v,"approved"); Request r=period("10:00","11:00");
        Preview p=availability.preview(v,r); booking(v,"pending");
        assertThatThrownBy(() -> availability.create(v,actor,new Request(r.startDateTime(),r.endDateTime(),r.reason(),
                p.affectedBookings().stream().map(Booking::bookingId).toList(),p.previewToken())))
                .isInstanceOf(VenueBookingStateException.class);
        assertThat(availability.list(v)).isEmpty();
    }
    @Test void failedInsertRollsBackAllEffects() {
        UUID v=venue(), b=booking(v,"approved"); Request r=period("10:00","11:00"); Preview p=availability.preview(v,r);
        assertThatThrownBy(() -> availability.create(v,UUID.randomUUID(),new Request(r.startDateTime(),r.endDateTime(),r.reason(),
                List.of(b),p.previewToken()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(availability.list(v)).isEmpty(); assertThat(availability.affected(b)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=?",Integer.class,b)).isZero();
    }
    @Test void unavailablePeriodBlocksShortlistSubmissionAndApproval() {
        UUID v=venue(); availability.updateSettings(v,new Settings(30,45)); UUID b=booking(v,"pending");
        record(v,period("09:20","09:40"));
        assertThat(requests.venueOptions(coordinator,event(b)).stream().filter(o -> o.venue().venueId().equals(v)).findFirst().orElseThrow().verdict()).isEqualTo("blocked");
        assertThatThrownBy(() -> bookings.approve(b)).isInstanceOf(VenueBookingStateException.class);
        UUID other=booking(venue(),"rejected");
        assertThatThrownBy(() -> requests.submit(coordinator,event(other),new SubmitVenueBookingRequest(v,null,null)))
                .isInstanceOf(InvalidVenueBookingException.class);
    }
    @Test void bufferAwareBookingConflicts() {
        UUID v=venue(); availability.updateSettings(v,new Settings(30,45)); booking(v,"approved");
        assertThat(availability.conflicts(v,UUID.randomUUID(),period("13:14","14:00").startDateTime(),period("13:14","14:00").endDateTime())).hasSize(1);
        assertThat(availability.conflicts(v,UUID.randomUUID(),period("13:15","14:00").startDateTime(),period("13:15","14:00").endDateTime())).isEmpty();
    }
    @Test void replacementApprovalChangesOnlyOriginalStatus() {
        UUID old=venue(), next=venue(), b=booking(old,"approved"); record(old,period("10:00","11:00"));
        var replacement=requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,"Replacement",null,b));
        assertThat(statusOf(b)).isEqualTo("approved");
        assertThatThrownBy(() -> requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,null,null,b))).isInstanceOf(VenueBookingStateException.class);
        bookings.approve(replacement.bookingId());
        assertThat(statusOf(b)).isEqualTo("changed"); assertThat(statusOf(replacement.bookingId())).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT venue_id FROM events WHERE event_id=?",UUID.class,event(b))).isEqualTo(next);
        assertThat(availability.affected(b)).isFalse();
        assertThat(jdbc.queryForObject("SELECT booking_notes FROM venue_bookings WHERE booking_id=?",String.class,b)).isEqualTo("Preserve me");
    }
    @Test void rejectedAndWithdrawnReplacementsKeepOriginalAndAllowRetry() {
        UUID old=venue(), next=venue(), b=booking(old,"pending"); record(old,period("10:00","11:00"));
        var first=requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,null,null,b));
        bookings.reject(first.bookingId(),"No longer suitable",null,null);
        assertThat(statusOf(b)).isEqualTo("pending"); assertThat(availability.affected(b)).isTrue();
        var second=requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,null,null,b));
        requests.cancel(coordinator,event(b),second.bookingId(),"Try another venue");
        assertThat(availability.affected(b)).isTrue();
        assertThat(requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,null,null,b)).status().name()).isEqualTo("pending");
    }
    @Test void confirmedEventCanRequestReplacementButAnotherCoordinatorCannot() {
        UUID v=venue(), b=booking(v,"approved"), next=venue(); record(v,period("10:00","11:00"));
        jdbc.update("UPDATE events SET status='confirmed' WHERE event_id=?",event(b));
        assertThatThrownBy(() -> requests.submit(UUID.randomUUID(),event(b),new SubmitVenueBookingRequest(next,null,null,b)))
                .isInstanceOf(com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException.class);
        assertThat(requests.submit(coordinator,event(b),new SubmitVenueBookingRequest(next,null,null,b))).isNotNull();
    }
    @Test void notificationFailureRollsBackPeriodAndAffectedLinks() {
        UUID v=venue(), b=booking(v,"approved");
        // Only this test's booking can trigger the injected failure.
        jdbc.execute("CREATE FUNCTION vs01_fail_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'VS01 notification failure'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER vs01_fail_notification BEFORE INSERT ON notifications FOR EACH ROW WHEN (NEW.venue_booking_id='"+b+"'::uuid) EXECUTE FUNCTION vs01_fail_notification()");
            assertThatThrownBy(() -> record(v,period("10:00","11:00"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(availability.list(v)).isEmpty();
            assertThat(availability.affected(b)).isFalse();
            assertThat(statusOf(b)).isEqualTo("approved");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS vs01_fail_notification ON notifications");
            jdbc.execute("DROP FUNCTION IF EXISTS vs01_fail_notification()");
        }
    }
    @Test void createAndScheduleWorkThroughAuthenticatedApi() throws Exception {
        UUID v=venue();
        mvc.perform(post("/api/venues/"+v+"/unavailability").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDateTime\":\"2027-04-01T10:00:00+08:00\",\"endDateTime\":\"2027-04-01T11:00:00+08:00\",\"reason\":\"  Maintenance  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("Maintenance"));
        mvc.perform(get("/api/venues/"+v+"/schedule").with(flow.as("vs1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unavailablePeriods[0].reason").value("Maintenance"));
        mvc.perform(put("/api/venues/"+v+"/occupancy-settings").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setupMinutes\":0.5,\"turnaroundMinutes\":0}")).andExpect(status().is4xxClientError());
    }
    @Test void invalidCalendarAndPastDatesAreRejectedByBothEndpointsWithoutWrites() throws Exception {
        UUID v=venue();
        String yesterday=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Singapore")).minusDays(1)+"T10:00:00+08:00";
        for (String endpoint : List.of("/unavailability", "/unavailability/preview")) {
            for (String[] dates : List.of(
                    new String[]{"2027-02-29T10:00:00+08:00","2027-03-01T11:00:00+08:00"},
                    new String[]{"2027-04-30T10:00:00+08:00","2027-04-31T11:00:00+08:00"},
                    new String[]{yesterday,"2028-01-01T10:00:00+08:00"})) {
                mvc.perform(post("/api/venues/"+v+endpoint).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDateTime\":\""+dates[0]+"\",\"endDateTime\":\""+dates[1]+"\",\"reason\":\"Maintenance\"}"))
                        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").exists());
            }
        }
        assertThat(availability.list(v)).isEmpty();
    }
    @Test void permissionValidationAndSettingsBoundaries() throws Exception {
        UUID v=venue(); String url="/api/venues/"+v+"/unavailability";
        mvc.perform(get(url).with(flow.as("ec1"))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(url).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/venues/"+UUID.randomUUID()+"/unavailability").with(flow.as("vs1"))).andExpect(status().isNotFound());
        for (Request r : List.of(period("10:00","10:00"),period("11:00","10:00"),
                new Request(period("10:00","11:00").startDateTime(),period("10:00","11:00").endDateTime(),"  ",null,null)))
            assertThatThrownBy(() -> availability.create(v,actor,r)).isInstanceOf(InvalidVenueBookingException.class);
        assertThatThrownBy(() -> availability.updateSettings(v,new Settings(-1,0))).isInstanceOf(InvalidVenueBookingException.class);
        mvc.perform(put("/api/venues/"+v+"/occupancy-settings").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setupMinutes\":30,\"turnaroundMinutes\":45}")).andExpect(status().isOk()).andExpect(jsonPath("$.setupMinutes").value(30));
        booking(v,"pending");
        assertThatThrownBy(() -> availability.updateSettings(v,new Settings(15,0))).isInstanceOf(VenueBookingStateException.class);
    }
    @Test void simultaneousRecordingAndApprovalCannotSilentlyMissEachOther() throws Exception {
        UUID v=venue(), b=booking(v,"pending"); Request r=period("10:00","11:00"); Preview p=availability.preview(v,r);
        CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var create=pool.submit(() -> { start.await(); try {
                availability.create(v,actor,new Request(r.startDateTime(),r.endDateTime(),r.reason(),List.of(b),p.previewToken())); return true;
            } catch (VenueBookingStateException ex) { return false; } });
            var approve=pool.submit(() -> { start.await(); try { bookings.approve(b); return true; }
                catch (VenueBookingStateException ex) { return false; } });
            start.countDown();
            boolean created=create.get(15,TimeUnit.SECONDS), approved=approve.get(15,TimeUnit.SECONDS);
            assertThat(created && approved).isFalse();
            assertThat(created || approved).isTrue();
            if (created) { assertThat(availability.affected(b)).isTrue(); assertThat(statusOf(b)).isEqualTo("pending"); }
        }
    }
}
