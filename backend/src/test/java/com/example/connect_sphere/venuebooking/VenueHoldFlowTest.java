package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venuebooking.hold.*;
import com.example.connect_sphere.venuebooking.service.*;

@Tag("integration") @Tag("VS-C3") @Tag("VS-C4") @Tag("VS08")
@SpringBootTest(properties="venue.holds.expiry-delay-ms=86400000")
@AutoConfigureMockMvc
@Import(VenueHoldFlowTest.TimeConfig.class)
class VenueHoldFlowTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2027-03-01T00:00:00Z"));
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time.get();}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testHoldClock(){return new MutableClock();} }
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired VenueHoldService holds;
    @Autowired VenueHoldRepository repository;
    @Autowired VenueHoldExpiryJob expiryJob;
    @Autowired VenueBookingService bookings;
    @Autowired VenueBookingRequestService requests;
    @Autowired AvailabilityService availability;
    @Autowired MockMvc mvc;
    final List<UUID> eventIds=new ArrayList<>(),venueIds=new ArrayList<>();
    UUID actor,coordinator;
    OffsetDateTime expiry;
    @BeforeEach void setup(){
        clock.time.set(Instant.parse("2027-03-01T00:00:00Z"));
        expiry=OffsetDateTime.now(clock).plusHours(1);
        actor=jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'",UUID.class);
        coordinator=jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec1'",UUID.class);
    }
    UUID venue(){
        UUID id=venues.createVenue(new CreateVenueDto("VS-C3 "+UUID.randomUUID(),200,List.of(VenueLayout.classroom),"Daily",null,null,null)).venueId();
        venueIds.add(id);
        jdbc.update("INSERT INTO venue_operating_hours(operating_hour_id,venue_id,day_of_week,open_time,close_time) SELECT gen_random_uuid(),?,day,'00:00'::time,'23:59'::time FROM generate_series(1,7) day",id);
        return id;
    }
    UUID booking(UUID venue,String start,String end){
        UUID event=UUID.randomUUID(),id=UUID.randomUUID();eventIds.add(event);
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,accessibility_needs,status,coordinator_id)
            VALUES (?,'Hold workshop','Test',?::timestamptz,?::timestamptz,100,'Seating','{}'::accessibilities[],'pending',?)
            """,event,start,end,coordinator);
        jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES (?,?,?,'pending',?::timestamptz,?::timestamptz)",id,venue,event,start,end);
        return id;
    }
    UUID booking(UUID v){return booking(v,"2027-04-01T10:00:00+08:00","2027-04-01T12:00:00+08:00");}
    String statusOf(UUID id){return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,id);}
    @AfterEach void clean(){
        jdbc.execute("DROP TRIGGER IF EXISTS vsc3_fail_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS vsc3_fail_notification()");
        jdbc.execute("DROP TRIGGER IF EXISTS vsc3_fail_approval ON venue_bookings");
        jdbc.execute("DROP FUNCTION IF EXISTS vsc3_fail_approval()");
        for(UUID event:eventIds){
            jdbc.update("DELETE FROM notifications WHERE event_id=?",event);
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?",event);
            jdbc.update("DELETE FROM events WHERE event_id=?",event);
        }
        for(UUID v:venueIds){jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",v);jdbc.update("DELETE FROM venues WHERE venue_id=?",v);}
    }
    @Test @DisplayName("VS-C3 creation/expiry (boundary): exact server deadline frees occupancy before cleanup; notification once")
    void expiryBoundary(){
        UUID v=venue(),b=booking(v),competitor=booking(v);
        var hold=holds.create(b,expiry,actor);
        assertThat(statusOf(b)).isEqualTo("pending");
        assertThat(hold.status()).isEqualTo("active");
        assertThat(hold.expiresAt().toInstant()).isEqualTo(expiry.toInstant());
        assertThatThrownBy(()->bookings.approve(competitor)).isInstanceOf(VenueBookingConflictException.class);
        clock.time.set(expiry.toInstant().minusNanos(1000));
        assertThat(repository.blocking(v)).hasSize(1);
        clock.time.set(expiry.toInstant());
        assertThat(repository.blocking(v)).isEmpty();
        assertThat(holds.history(b).getFirst().status()).isEqualTo("expired");
        assertThatThrownBy(()->bookings.approve(b,hold.holdId())).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(()->holds.release(hold.holdId(),actor)).isInstanceOf(VenueBookingStateException.class);
        bookings.approve(competitor);
        expiryJob.expire();expiryJob.expire();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=? AND type='venue_hold_expired'",Integer.class,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recipient_user_id FROM notifications WHERE venue_booking_id=? AND type='venue_hold_expired'",UUID.class,b)).isEqualTo(coordinator);
        assertThat(holds.history(b).getFirst().endedAt().toInstant()).isEqualTo(expiry.toInstant());
        assertThat(statusOf(competitor)).isEqualTo("approved");
    }
    @Test @DisplayName("VS-C3 validation (negative): missing/past/equal expiry and invalid booking leave no holds")
    void validation(){
        UUID b=booking(venue());
        for(var invalid:Arrays.asList(null,OffsetDateTime.now(clock),OffsetDateTime.now(clock).minusSeconds(1)))
            assertThatThrownBy(()->holds.create(b,invalid,actor)).isInstanceOf(InvalidVenueBookingException.class);
        assertThatThrownBy(()->holds.create(UUID.randomUUID(),expiry,actor)).isInstanceOf(VenueBookingNotFoundException.class);
        assertThat(holds.history(b)).isEmpty();
        bookings.approve(b);
        assertThatThrownBy(()->holds.create(b,expiry,actor)).isInstanceOf(VenueBookingStateException.class);
    }
    @Test @DisplayName("VS-C3 VS-C4 schedule (regression): both schedule APIs retain active holds and read-only buffer conflicts")
    void integratedSchedule() throws Exception {
        UUID v=venue(),a=booking(v,"2027-04-01T09:00:00+08:00","2027-04-01T10:00:00+08:00"),
            b=booking(v,"2027-04-01T10:30:00+08:00","2027-04-01T12:00:00+08:00"),
            pending=booking(v,"2027-04-01T14:00:00+08:00","2027-04-01T15:00:00+08:00");
        bookings.approve(a);bookings.approve(b);
        // Represent a legacy conflict introduced by saved turnaround durations.
        jdbc.update("UPDATE venue_bookings SET turnaround_duration_minutes=45 WHERE booking_id=?",a);
        var hold=holds.create(pending,expiry,actor);
        for(boolean bounded:List.of(false,true)) {
            var request=get("/api/venues/"+v+"/schedule")
                .with(jwt().jwt(j->j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_VS")));
            if(bounded) request.param("start","2027-04-01T00:00:00+08:00").param("end","2027-04-02T00:00:00+08:00");
            mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.holds.length()").value(1))
                .andExpect(jsonPath("$.holds[0].holdId").value(hold.holdId().toString()))
                .andExpect(jsonPath("$.holds[0].expiresAt").exists())
                .andExpect(jsonPath("$.holds[0].status").value("active"))
                .andExpect(jsonPath("$.activeConflicts.length()").value(1));
        }
        clock.time.set(expiry.toInstant());
        assertThat(availability.schedule(v).holds()).isEmpty();
        assertThat(availability.schedule(v).activeConflicts()).hasSize(1);
        assertThat(holds.history(pending).getFirst().endedAt()).isNull();
        assertThat(statusOf(a)).isEqualTo("approved");assertThat(statusOf(b)).isEqualTo("approved");
        assertThat(statusOf(pending)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id IN (SELECT event_id FROM venue_bookings WHERE venue_id=?)",Integer.class,v)).isZero();
    }
    @Test @DisplayName("VS-C3 release (regression): manual release frees approval and removes calendar hold without modifying other reservations")
    void releaseFreesApproval(){
        UUID v=venue(),pending=booking(v),competitor=booking(v),other=booking(v,"2027-04-01T14:00:00+08:00","2027-04-01T15:00:00+08:00");
        bookings.approve(other);
        var hold=holds.create(pending,expiry,actor);
        assertThatThrownBy(()->bookings.approve(competitor)).isInstanceOf(VenueBookingConflictException.class);
        assertThat(statusOf(competitor)).isEqualTo("pending");
        holds.release(hold.holdId(),actor);
        bookings.approve(competitor);
        assertThat(availability.schedule(v).holds()).isEmpty();
        assertThat(statusOf(pending)).isEqualTo("pending");assertThat(statusOf(other)).isEqualTo("approved");
        assertThat(statusOf(competitor)).isEqualTo("approved");
    }
    @Test @DisplayName("VS-C3 conflicts (boundary): buffers reserve once; touching intervals and different venues allowed")
    void occupiedIntervals(){
        UUID v=venue(),b=booking(v);
        jdbc.update("UPDATE venue_bookings SET setup_duration_minutes=30,turnaround_duration_minutes=45 WHERE booking_id=?",b);
        var hold=holds.create(b,expiry,actor);
        assertThat(hold.reservedStart().toInstant()).isEqualTo(Instant.parse("2027-04-01T01:30:00Z"));
        assertThat(hold.reservedEnd().toInstant()).isEqualTo(Instant.parse("2027-04-01T04:45:00Z"));
        UUID overlaps=booking(v,"2027-04-01T12:30:00+08:00","2027-04-01T14:00:00+08:00");
        assertThatThrownBy(()->holds.create(overlaps,expiry,actor)).isInstanceOf(VenueBookingStateException.class);
        UUID adjacent=booking(v,"2027-04-01T12:45:00+08:00","2027-04-01T14:00:00+08:00");
        holds.create(adjacent,expiry,actor);
        holds.create(booking(venue()),expiry,actor);
        assertThat(repository.blocking(v)).hasSize(2);
    }
    @Test @DisplayName("VS-C3 release/conversion (happy/negative): history retained, no duplicate booking, released conversion rejected")
    void releaseAndConvert(){
        UUID v=venue(),b=booking(v);var first=holds.create(b,expiry,actor);
        assertThatThrownBy(()->bookings.approve(b)).isInstanceOf(VenueBookingStateException.class);
        holds.release(first.holdId(),actor);
        assertThat(repository.blocking(v)).isEmpty();
        assertThatThrownBy(()->holds.release(first.holdId(),actor)).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(()->bookings.approve(b,first.holdId())).isInstanceOf(VenueBookingStateException.class);
        var second=holds.create(b,expiry,actor);
        bookings.approve(b,second.holdId());
        assertThat(statusOf(b)).isEqualTo("approved");
        assertThat(holds.history(b)).extracting(VenueHold::status).containsExactlyInAnyOrder("released","converted");
        assertThat(repository.blocking(v)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE venue_id=?",Integer.class,v)).isEqualTo(1);
        assertThat(availability.schedule(v).holds()).isEmpty();
        assertThat(availability.schedule(v).bookings()).extracting(AvailabilityModels.Booking::status).containsExactly("approved");
    }
    @Test @DisplayName("VS-C3 expiry (failure): failed notification rolls back persisted expiry and retries once, but never blocks availability")
    void notificationRetry(){
        UUID v=venue(),b=booking(v);var hold=holds.create(b,expiry,actor);clock.time.set(expiry.toInstant());
        jdbc.execute("CREATE FUNCTION vsc3_fail_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER vsc3_fail_notification BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION vsc3_fail_notification()");
        assertThatThrownBy(()->holds.expire(hold.holdId())).isInstanceOf(RuntimeException.class);
        assertThat(repository.blocking(v)).isEmpty();
        assertThat(holds.history(b).getFirst().endedAt()).isNull();
        jdbc.execute("DROP TRIGGER vsc3_fail_notification ON notifications");
        holds.expire(hold.holdId());holds.expire(hold.holdId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=?",Integer.class,b)).isEqualTo(1);
    }
    @Test @DisplayName("VS-C3 conversion (failure): approval failure rolls back hold consumption")
    void conversionRollback(){
        UUID b=booking(venue());var hold=holds.create(b,expiry,actor);
        jdbc.execute("CREATE FUNCTION vsc3_fail_approval() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER vsc3_fail_approval BEFORE UPDATE ON venue_bookings FOR EACH ROW EXECUTE FUNCTION vsc3_fail_approval()");
        assertThatThrownBy(()->bookings.approve(b,hold.holdId())).isInstanceOf(RuntimeException.class);
        assertThat(statusOf(b)).isEqualTo("pending");
        assertThat(holds.history(b).getFirst().status()).isEqualTo("active");
    }
    private int race(Callable<?> a,Callable<?> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<Boolean>> results=new ArrayList<>();
            for(var action:List.of(a,b)) results.add(pool.submit(()->{start.await();try{action.call();return true;}catch(VenueBookingStateException expected){return false;}}));
            start.countDown();int wins=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))wins++;return wins;
        }
    }
    @Test @DisplayName("VS-C3 VS08 concurrency (conflict): overlapping hold creation serialises on the venue")
    void concurrentHolds() throws Exception {
        UUID v=venue(),a=booking(v),b=booking(v);
        assertThat(race(()->holds.create(a,expiry,actor),()->holds.create(b,expiry,actor))).isEqualTo(1);
        assertThat(repository.blocking(v)).hasSize(1);
    }
    @Test @DisplayName("VS-C3 VS03 concurrency (conflict): hold creation versus approval commits only one reservation")
    void holdVersusApproval() throws Exception {
        UUID v=venue(),a=booking(v),b=booking(v);
        assertThat(race(()->holds.create(a,expiry,actor),()->bookings.approve(b))).isEqualTo(1);
    }
    @Test @DisplayName("VS-C3 conversion concurrency (conflict): release versus conversion has one winner")
    void releaseVersusConversion() throws Exception {
        UUID b=booking(venue());var hold=holds.create(b,expiry,actor);
        assertThat(race(()->holds.release(hold.holdId(),actor),()->bookings.approve(b,hold.holdId()))).isEqualTo(1);
    }
    @Test @DisplayName("VS-C3 conversion concurrency (conflict): two conversions approve once")
    void duplicateConversion() throws Exception {
        UUID b=booking(venue());var hold=holds.create(b,expiry,actor);
        assertThat(race(()->bookings.approve(b,hold.holdId()),()->bookings.approve(b,hold.holdId()))).isEqualTo(1);
    }
    @Test @DisplayName("VS-C3 VS25 release (regression): cancellation and rejection release holds, keeping attempt history")
    void cancelledOrRejected(){
        UUID v=venue(),b=booking(v);var hold=holds.create(b,expiry,actor);
        requests.cancel(coordinator,repository.history(b).getFirst().eventId(),b,"No longer needed");
        assertThat(repository.blocking(v)).isEmpty();
        assertThat(holds.history(b).getFirst().status()).isEqualTo("released");
        UUID rejected=booking(v);holds.create(rejected,expiry,actor);
        bookings.reject(rejected,"No longer suitable",null,null);
        assertThat(holds.history(rejected).getFirst().status()).isEqualTo("released");
        assertThatThrownBy(()->bookings.approve(b,hold.holdId())).isInstanceOf(VenueBookingStateException.class);
    }
    @Test @DisplayName("VS-C3 expiry/conversion (conflict): at deadline only expiry can succeed")
    void expiryVersusConversion() throws Exception {
        UUID b=booking(venue());var hold=holds.create(b,expiry,actor);clock.time.set(expiry.toInstant());
        assertThat(race(()->{holds.expire(hold.holdId());return null;},()->bookings.approve(b,hold.holdId()))).isEqualTo(1);
        assertThat(statusOf(b)).isEqualTo("pending");
        assertThat(holds.history(b).getFirst().status()).isEqualTo("expired");
    }
    @Test @DisplayName("VS-C3 VS-C2 (negative): active hold protects its saved occupied interval from timing edits")
    void timingProtected(){
        UUID b=booking(venue());holds.create(b,expiry,actor);
        assertThatThrownBy(()->bookings.updateTiming(b,actor,null)).isInstanceOf(VenueBookingStateException.class)
            .hasMessageContaining("Release the active tentative hold");
    }
    @Test @DisplayName("VS-C3 VS03 (conflict): a hold cannot overlap an already approved booking")
    void approvedConflict(){
        UUID v=venue(),approved=booking(v),pending=booking(v);
        bookings.approve(approved);
        assertThatThrownBy(()->holds.create(pending,expiry,actor)).isInstanceOf(VenueBookingStateException.class).hasMessageContaining("conflicts");
        assertThat(holds.history(pending)).isEmpty();
        assertThat(statusOf(approved)).isEqualTo("approved");
    }
    @Test @DisplayName("VS-C3 hold conflict API (conflict): return structured booking and occupied dates for the shared error cards")
    void structuredConflict() throws Exception {
        UUID v=venue(),approved=booking(v),pending=booking(v);
        bookings.approve(approved);
        mvc.perform(post("/api/venue-bookings/"+pending+"/holds")
            .with(jwt().jwt(j->j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_VS")))
            .contentType("application/json").content("{\"expiresAt\":\"2027-03-01T01:00:00Z\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conflicts[0].bookingId").value(approved.toString()))
            .andExpect(jsonPath("$.conflicts[0].effectiveStart").exists())
            .andExpect(jsonPath("$.conflicts[0].effectiveEnd").exists());
        assertThat(holds.history(pending)).isEmpty();
    }
    @Test @DisplayName("VS-C3 VS-C2 conversion (conflict): a changed legacy booking window cannot consume the old hold")
    void changedInterval(){
        UUID b=booking(venue());var hold=holds.create(b,expiry,actor);
        jdbc.update("UPDATE venue_bookings SET end_datetime=end_datetime+interval '1 hour' WHERE booking_id=?",b);
        assertThatThrownBy(()->bookings.approve(b,hold.holdId())).isInstanceOf(VenueBookingStateException.class).hasMessageContaining("period changed");
        assertThat(statusOf(b)).isEqualTo("pending");
        assertThat(holds.history(b).getFirst().status()).isEqualTo("active");
    }
    @Test @DisplayName("VS-C3 availability (negative): recorded unavailability and operating hours prevent creation")
    void unavailableAndHours(){
        UUID v=venue(),b=booking(v),period=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO venue_unavailability(unavailability_id,venue_id,start_datetime,end_datetime,reason,created_by) VALUES (?,?,'2027-04-01T11:00:00+08:00','2027-04-01T13:00:00+08:00','Maintenance',?)",period,v,actor);
            assertThatThrownBy(()->holds.create(b,expiry,actor)).isInstanceOf(VenueBookingStateException.class).hasMessageContaining("unavailable");
        } finally {jdbc.update("DELETE FROM venue_unavailability WHERE unavailability_id=?",period);}
        jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",v);
        assertThatThrownBy(()->holds.create(b,expiry,actor)).isInstanceOf(VenueBookingStateException.class).hasMessageContaining("opening hours");
        assertThat(holds.history(b)).isEmpty();
    }
    @Test @DisplayName("VS-C3 RBAC (cross-cutting): only Venue Staff can create, release, convert or read staff hold history")
    void accessBoundaries() throws Exception {
        UUID b=booking(venue());
        for(String role:List.of("EC","EO","ECL","ATT","TECHNICIAN","SAFETY_OFFICER")){
            var identity=jwt().jwt(j->j.subject(coordinator.toString())).authorities(new SimpleGrantedAuthority("ROLE_"+role));
            mvc.perform(post("/api/venue-bookings/"+b+"/holds").with(identity).contentType("application/json").content("{\"expiresAt\":\"2027-03-01T01:00:00Z\"}")).andExpect(status().isForbidden());
            mvc.perform(get("/api/venue-bookings/"+b+"/holds").with(identity)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/venue-holds/"+UUID.randomUUID()+"/release").with(identity)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/venue-holds/"+UUID.randomUUID()+"/convert").with(identity)).andExpect(status().isForbidden());
        }
        assertThat(holds.history(b)).isEmpty();
        mvc.perform(post("/api/venue-bookings/"+b+"/holds").with(jwt().jwt(j->j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_VS")))
            .contentType("application/json").content("{\"expiresAt\":\"2027-03-01T01:00:00Z\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("active"));
    }
}
