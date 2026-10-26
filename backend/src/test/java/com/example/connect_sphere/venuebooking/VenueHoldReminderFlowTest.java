package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.hold.*;
import com.example.connect_sphere.venuebooking.service.VenueBookingService;

/** EC-C3: the coordinator is reminded before a tentative venue hold expires (default lead 24 hours). */
@Tag("integration") @Tag("EC-C3")
@Tag("VS17")
@SpringBootTest(properties="venue.holds.expiry-delay-ms=86400000")
@AutoConfigureMockMvc
@Import(VenueHoldReminderFlowTest.TimeConfig.class)
class VenueHoldReminderFlowTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2099-03-01T00:00:00Z"));
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time.get();}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testReminderClock(){return new MutableClock();} }
    static final Instant T0=Instant.parse("2099-03-01T00:00:00Z");
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired VenueHoldService holds;
    @Autowired VenueHoldRepository repository;
    @Autowired VenueHoldReminderJob reminderJob;
    @Autowired VenueHoldExpiryJob expiryJob;
    @Autowired VenueBookingService bookings;
    @Autowired NotificationService notifications;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    final List<UUID> eventIds=new ArrayList<>(),venueIds=new ArrayList<>();
    UUID staff,coordinator,otherCoordinator;
    @BeforeEach void setup(){
        clock.time.set(T0);
        staff=jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'",UUID.class);
        coordinator=jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec1'",UUID.class);
        otherCoordinator=jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec2'",UUID.class);
    }
    @AfterEach void clean(){
        jdbc.execute("DROP TRIGGER IF EXISTS ecc3_fail_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS ecc3_fail_notification()");
        // API-built events also have a request and append-only timeline rows; remove them as NotificationFlowTest does.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE event_request_activity DISABLE TRIGGER trg_activity_append_only");
            for(UUID event:eventIds){
                jdbc.update("DELETE FROM notifications WHERE event_id=? OR event_request_id IN (SELECT request_id FROM event_requests WHERE event_id=?)",event,event);
                jdbc.update("DELETE FROM event_request_activity WHERE request_id IN (SELECT request_id FROM event_requests WHERE event_id=?)",event);
                jdbc.update("DELETE FROM event_requests WHERE event_id=?",event);
                jdbc.update("DELETE FROM venue_bookings WHERE event_id=?",event);
                jdbc.update("DELETE FROM events WHERE event_id=?",event);
            }
            jdbc.execute("ALTER TABLE event_request_activity ENABLE TRIGGER trg_activity_append_only");
        });
        for(UUID v:venueIds){jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",v);jdbc.update("DELETE FROM venues WHERE venue_id=?",v);}
    }
    String venueName;
    UUID venue(){
        venueName="EC-C3 Hall "+UUID.randomUUID();
        UUID id=venues.createVenue(new CreateVenueDto(venueName,200,List.of(VenueLayout.classroom),"Daily",null,null,null, com.example.connect_sphere.testsupport.VenueHoursFixtures.allWeek())).venueId();
        venueIds.add(id);
        return id;
    }
    /** A pending booking for a Planning event coordinated by ec1. */
    UUID booking(UUID venue){
        UUID event=UUID.randomUUID();eventIds.add(event);
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,accessibility_needs,status,coordinator_id)
            VALUES (?,'Alumni Gala','Test','2099-04-01T10:00:00+08:00','2099-04-01T12:00:00+08:00',100,'Seating','{}'::accessibilities[],'pending',?)
            """,event,coordinator);
        return bookingFor(venue,event);
    }
    UUID bookingFor(UUID venue,UUID event){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES (?,?,?,'pending','2099-04-01T10:00:00+08:00','2099-04-01T12:00:00+08:00')",id,venue,event);
        return id;
    }
    VenueHold hold(UUID booking,Duration left){return holds.create(booking,OffsetDateTime.ofInstant(clock.instant().plus(left),ZoneOffset.UTC),staff);}
    int count(UUID booking,String type){
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=? AND type::text=?",Integer.class,booking,type);
    }
    int reminders(UUID booking){return count(booking,"venue_hold_expiring");}
    OffsetDateTime remindedAt(UUID holdId){
        return jdbc.queryForObject("SELECT reminded_at FROM venue_booking_holds WHERE hold_id=?",OffsetDateTime.class,holdId);
    }
    UUID eventOf(UUID booking){return jdbc.queryForObject("SELECT event_id FROM venue_bookings WHERE booking_id=?",UUID.class,booking);}
    List<UUID> recipients(UUID booking,String type){
        return jdbc.queryForList("SELECT recipient_user_id FROM notifications WHERE venue_booking_id=? AND type::text=?",UUID.class,booking,type);
    }

    @Test @DisplayName("EC-C3 AC1 (happy): the coordinator gets one reminder naming the event, venue and SGT expiry, linking to the venue booking page")
    void remindsTheCoordinator(){
        UUID b=booking(venue());
        hold(b,Duration.ofHours(20)); // 2099-03-01T20:00Z + 8 h = 2 Mar 2099, 4:00 AM in Singapore
        reminderJob.remind();
        assertThat(recipients(b,"venue_hold_expiring")).containsExactly(coordinator);
        NotificationDto sent=notifications.list(coordinator).stream()
            .filter(n->n.type().equals("venue_hold_expiring") && eventOf(b).equals(n.eventId())).findFirst().orElseThrow();
        assertThat(sent.eventName()).isEqualTo("Alumni Gala");
        assertThat(sent.message()).contains("Alumni Gala").contains(venueName).contains("2 Mar 2099, 4:00 AM (Singapore time)");
        assertThat(sent.linkPath()).isEqualTo("/coordinator/events/"+eventOf(b)+"/venue-booking");
        assertThat(sent.read()).isFalse();
    }

    @Test @DisplayName("EC-C3 AC2 (boundary): a hold expiring exactly 24 hours ahead is reminded; one second later waits until it enters the window")
    void windowEdge(){
        UUID atEdge=booking(venue()),beyond=booking(venue());
        var edgeHold=hold(atEdge,Duration.ofHours(24));
        var laterHold=hold(beyond,Duration.ofHours(24).plusSeconds(1));
        reminderJob.remind();
        assertThat(reminders(atEdge)).isEqualTo(1);
        assertThat(reminders(beyond)).isZero();
        assertThat(remindedAt(laterHold.holdId())).isNull();
        clock.time.set(T0.plusSeconds(1));
        reminderJob.remind();
        assertThat(reminders(beyond)).isEqualTo(1);
        assertThat(reminders(atEdge)).isEqualTo(1);
        assertThat(remindedAt(edgeHold.holdId()).toInstant()).isEqualTo(T0);
        assertThat(remindedAt(laterHold.holdId()).toInstant()).isEqualTo(T0.plusSeconds(1));
    }

    @Test @DisplayName("EC-C3 AC3 (conflict): repeated checks send a hold's reminder only once")
    void onlyOnce(){
        UUID b=booking(venue());hold(b,Duration.ofHours(5));
        reminderJob.remind();reminderJob.remind();
        clock.time.set(T0.plus(Duration.ofHours(1)));
        reminderJob.remind();
        assertThat(reminders(b)).isEqualTo(1);
    }

    @Test @DisplayName("EC-C3 AC3 (conflict): two concurrent checks for the same hold send one reminder")
    void concurrentChecks() throws Exception {
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(5));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{start.await();return holds.remind(hold.holdId());}));
            start.countDown();
            int sent=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))sent++;
            assertThat(sent).isEqualTo(1);
        }
        assertThat(reminders(b)).isEqualTo(1);
    }

    @Test @DisplayName("EC-C3 AC3 (happy): a new hold on the same booking after release gets its own reminder")
    void newHoldNewReminder(){
        UUID b=booking(venue());
        var first=hold(b,Duration.ofHours(2));
        reminderJob.remind();
        holds.release(first.holdId(),staff);
        var second=hold(b,Duration.ofHours(3));
        reminderJob.remind();
        assertThat(reminders(b)).isEqualTo(2);
        assertThat(remindedAt(second.holdId())).isNotNull();
    }

    @Test @DisplayName("EC-C3 AC4 (boundary): a hold created with less time left than the window is reminded at the next check")
    void createdInsideWindow(){
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(2));
        assertThat(reminders(b)).isZero();
        reminderJob.remind();
        assertThat(reminders(b)).isEqualTo(1);
        assertThat(remindedAt(hold.holdId())).isNotNull();
    }

    @Test @DisplayName("EC-C3 AC4 AC6 (negative): a hold that expired before any check gets no reminder, only the expiry notice")
    void alreadyExpired(){
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(1));
        clock.time.set(T0.plus(Duration.ofHours(1))); // exactly at expiry: it is no longer held
        reminderJob.remind();
        assertThat(holds.remind(hold.holdId())).isFalse();
        assertThat(reminders(b)).isZero();
        assertThat(remindedAt(hold.holdId())).isNull();
        expiryJob.expire();
        assertThat(count(b,"venue_hold_expired")).isEqualTo(1);
        assertThat(reminders(b)).isZero();
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={"released","converted","event cancelled","event completed"})
    @DisplayName("EC-C3 AC5 (negative): no reminder for a released or converted hold, or one whose event is cancelled or completed")
    void notReminded(String situation){
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(3));
        switch(situation){
            case "released" -> holds.release(hold.holdId(),staff);
            case "converted" -> bookings.approve(b,hold.holdId());
            case "event cancelled" -> jdbc.update("UPDATE events SET status='cancelled' WHERE event_id=?",eventOf(b));
            default -> jdbc.update("UPDATE events SET status='completed' WHERE event_id=?",eventOf(b));
        }
        reminderJob.remind();
        assertThat(holds.remind(hold.holdId())).isFalse();
        assertThat(reminders(b)).isZero();
        assertThat(remindedAt(hold.holdId())).isNull();
    }

    @Test @DisplayName("EC-C3 AC6 (happy): when a reminded hold expires, the coordinator is told once that the venue is no longer held")
    void expiryNotice(){
        UUID b=booking(venue());hold(b,Duration.ofHours(1));
        reminderJob.remind();
        clock.time.set(T0.plus(Duration.ofHours(1)));
        expiryJob.expire();expiryJob.expire();
        reminderJob.remind();
        assertThat(recipients(b,"venue_hold_expired")).containsExactly(coordinator);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE venue_booking_id=? AND type='venue_hold_expired'",String.class,b))
            .contains("expired").contains("no longer held");
        assertThat(reminders(b)).isEqualTo(1);
    }

    @Test @DisplayName("EC-C3 AC7 (conflict): after reassignment the reminder and expiry notice go to the new coordinator only")
    void currentCoordinator() throws Exception {
        // A real request: eo1 submits, ecl1 assigns ec1, ec1 approves; then the hold is placed.
        var flow=new FlowSupport(mvc,users);
        UUID event=flow.approvedEvent("eo1","ec1","""
            {"eventName":"EC-C3 Reassigned Gala %s","purpose":"Quarterly update","description":"All hands",
             "startDatetime":"2099-04-01T10:00:00+08:00","endDatetime":"2099-04-01T12:00:00+08:00",
             "expectedAttendance":100,"venueRequirements":"Theatre seating",
             "accessibilityNeeds":["none"],"registrationNeeds":false}
            """.formatted(UUID.randomUUID()));
        eventIds.add(event);
        UUID b=bookingFor(venue(),event);hold(b,Duration.ofHours(1));
        UUID requestId=jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id=?",UUID.class,event);
        // The Lead's reassignment (ELC-C5/ECL-C7) moves the request and its event to ec2.
        mvc.perform(post("/api/event-requests/assigned/"+requestId+"/reassign").with(flow.as("ecl1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"coordinatorUserId\":\""+otherCoordinator+"\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT coordinator_id FROM events WHERE event_id=?",UUID.class,event)).isEqualTo(otherCoordinator);
        reminderJob.remind();
        clock.time.set(T0.plus(Duration.ofHours(1)));
        expiryJob.expire();
        assertThat(recipients(b,"venue_hold_expiring")).containsExactly(otherCoordinator);
        assertThat(recipients(b,"venue_hold_expired")).containsExactly(otherCoordinator);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND recipient_user_id=? AND type::text IN ('venue_hold_expiring','venue_hold_expired')",
            Integer.class,event,coordinator)).isZero();
    }

    @Test @DisplayName("EC-C3 AC9 (cross-cutting): sending a reminder leaves the hold, booking and event unchanged")
    void reminderChangesNothingElse(){
        UUID v=venue(),b=booking(v);var hold=hold(b,Duration.ofHours(3));
        var event=eventOf(b);
        var before=jdbc.queryForMap("SELECT status::text AS status,coordinator_id,start_datetime,end_datetime FROM events WHERE event_id=?",event);
        reminderJob.remind();
        assertThat(reminders(b)).isEqualTo(1);
        var after=holds.history(b).getFirst();
        assertThat(after.status()).isEqualTo("active");
        assertThat(after.endedAt()).isNull();
        assertThat(after.expiresAt().toInstant()).isEqualTo(hold.expiresAt().toInstant());
        assertThat(after.reservedStart().toInstant()).isEqualTo(hold.reservedStart().toInstant());
        assertThat(after.reservedEnd().toInstant()).isEqualTo(hold.reservedEnd().toInstant());
        assertThat(jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,b)).isEqualTo("pending");
        assertThat(jdbc.queryForMap("SELECT status::text AS status,coordinator_id,start_datetime,end_datetime FROM events WHERE event_id=?",event)).isEqualTo(before);
        assertThat(repository.blocking(v)).hasSize(1);
    }

    @Test @DisplayName("EC-C3 AC8 (boundary): the coordinator's booking response gives each hold its reminder time, 24 hours before expiry")
    void reminderDueAtForTheCoordinator() throws Exception {
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(30));
        assertThat(holds.history(b).getFirst().reminderDueAt().toInstant()).isEqualTo(T0.plus(Duration.ofHours(6)));
        mvc.perform(get("/api/events/"+eventOf(b)+"/venue-bookings")
                .with(jwt().jwt(j->j.subject(coordinator.toString()).claim("role","ec")).authorities(new SimpleGrantedAuthority("ROLE_EC"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].holds[0].holdId").value(hold.holdId().toString()))
            .andExpect(jsonPath("$[0].holds[0].status").value("active"))
            .andExpect(jsonPath("$[0].holds[0].reminderDueAt").exists())
            .andExpect(jsonPath("$[0].holds[0].serverTime").exists());
    }

    @Test @DisplayName("EC-C3 AC10 (failure): a failed send leaves the hold unreminded; the next check sends exactly one")
    void failedSendRetries(){
        UUID b=booking(venue());var hold=hold(b,Duration.ofHours(3));
        jdbc.execute("CREATE FUNCTION ecc3_fail_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER ecc3_fail_notification BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION ecc3_fail_notification()");
        reminderJob.remind();
        assertThatThrownBy(()->holds.remind(hold.holdId())).isInstanceOf(RuntimeException.class);
        assertThat(remindedAt(hold.holdId())).isNull();
        assertThat(reminders(b)).isZero();
        jdbc.execute("DROP TRIGGER ecc3_fail_notification ON notifications");
        reminderJob.remind();reminderJob.remind();
        assertThat(reminders(b)).isEqualTo(1);
        assertThat(remindedAt(hold.holdId()).toInstant()).isEqualTo(T0);
    }
}
