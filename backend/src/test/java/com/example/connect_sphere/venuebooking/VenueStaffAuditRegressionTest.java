package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.dto.*;
import com.example.connect_sphere.venuebooking.service.*;
import com.example.connect_sphere.venuebooking.hold.*;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.venueavailability.AvailabilityService;

/** VS17 audit regressions exercise actual PostgreSQL transactions and row locks. */
@SpringBootTest @Tag("integration") @Tag("VS17") @Tag("EC-C2") @Tag("VS-C3")
class VenueStaffAuditRegressionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired VenueBookingRequestService requests;
    @Autowired VenueBookingService bookings;
    @Autowired VenueHoldService holds;
    @Autowired EventLifecycleService lifecycle;
    @Autowired AvailabilityService availability;
    @Autowired PlatformTransactionManager transactions;
    UUID ec,vs;
    final List<UUID> eventIds=new ArrayList<>(), venueIds=new ArrayList<>();
    @BeforeEach void setup() {
        ec=jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec1'",UUID.class);
        vs=jdbc.queryForObject("SELECT user_id FROM users WHERE username='vs1'",UUID.class);
    }
    @AfterEach void cleanup() {
        for(UUID id:eventIds) {
            jdbc.update("DELETE FROM notifications WHERE event_id=?",id);
            jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id'=?",id.toString());
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?",id);
            jdbc.update("DELETE FROM events WHERE event_id=?",id);
        }
        for(UUID id:venueIds) {
            jdbc.update("DELETE FROM venue_operational_issues WHERE venue_id=?",id);
            jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",id);
            jdbc.update("DELETE FROM venues WHERE venue_id=?",id);
        }
    }
    OffsetDateTime at(int hour) { return OffsetDateTime.parse("2031-04-01T%02d:00:00+08:00".formatted(hour)); }
    UUID venue() {
        UUID id=venues.createVenue(new CreateVenueDto("Audit "+UUID.randomUUID(),500,List.of(VenueLayout.classroom),"Daily",null,List.of(),List.of(),
            java.util.stream.IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIN,LocalTime.of(23,59))).toList(),BigDecimal.ZERO,BigDecimal.ZERO)).venueId();
        venueIds.add(id);return id;
    }
    UUID event() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,accessibility_needs,status,coordinator_id,organisation) VALUES (?,'Audit','Test',?,?,100,'Room','{}','pending',?,'Acme Pte Ltd')",id,at(8),at(18),ec);
        eventIds.add(id);
        return id;
    }
    SubmitVenueBookingRequest request(UUID venue,UUID source,int hour) {
        return new SubmitVenueBookingRequest(venue,null,null,source,at(hour),at(hour+2),BigDecimal.ZERO,BigDecimal.ZERO,null,null);
    }
    String state(UUID id) { return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,id); }
    long notices(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'venue_booking_id'=? AND payload->>'type'='venue_booking_approved'",Long.class,id.toString()); }
    @Test @DisplayName("VS17 (conflict): a rejected ancestor cannot branch around its pending or approved descendant")
    void rejectsBranchAcrossFullRequirement() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));bookings.reject(a.bookingId(),"Unavailable",null,null);
        var b=requests.submit(ec,e,request(venue(),a.bookingId(),10));bookings.reject(b.bookingId(),"Unavailable",null,null);
        var c=requests.submit(ec,e,request(venue(),b.bookingId(),10));UUID other=venue();
        long before=jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id=?",Long.class,e);
        assertThatThrownBy(() -> requests.submit(ec,e,request(other,a.bookingId(),10))).isInstanceOf(VenueBookingStateException.class);
        bookings.approve(c.bookingId());
        assertThatThrownBy(() -> requests.submit(ec,e,request(other,a.bookingId(),10))).isInstanceOf(VenueBookingStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id=?",Long.class,e)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE requirement_id=? AND status='approved'",Long.class,c.requirementId())).isEqualTo(1);
    }
    @Test @DisplayName("VS17 (conflict): concurrent replacement submissions share the event lock and admit only one successor")
    void concurrentBranchSubmission() throws Exception {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));bookings.reject(a.bookingId(),"Unavailable",null,null);
        var b=requests.submit(ec,e,request(venue(),a.bookingId(),10));bookings.reject(b.bookingId(),"Unavailable",null,null);
        UUID v1=venue(),v2=venue(); var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(() -> submitAfter(gate,e,request(v1,a.bookingId(),10)));
            var two=pool.submit(() -> submitAfter(gate,e,request(v2,b.bookingId(),10)));
            gate.countDown(); assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE requirement_id=? AND status='pending'",Long.class,a.requirementId())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    boolean submitAfter(CountDownLatch gate,UUID event,SubmitVenueBookingRequest request) throws Exception {
        gate.await();try { requests.submit(ec,event,request);return true; }catch(VenueBookingStateException expected) {return false;}
    }
    @Test @DisplayName("VS17 (conflict): pre-existing branched pending replacements cannot both be approved concurrently")
    void concurrentApprovalOfLegacyBranches() throws Exception {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));bookings.reject(a.bookingId(),"Unavailable",null,null);
        var rejected=requests.submit(ec,e,request(venue(),a.bookingId(),10));bookings.reject(rejected.bookingId(),"Unavailable",null,null);
        var b=requests.submit(ec,e,request(venue(),rejected.bookingId(),10));
        UUID branch=UUID.randomUUID(),v=venue();
        jdbc.update("INSERT INTO venue_bookings(booking_id,event_id,venue_id,status,start_datetime,end_datetime,replaces_booking_id,requirement_id) VALUES (?,?,?,'pending',?,?,?,?)",branch,e,v,at(10),at(12),a.bookingId(),a.requirementId());
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(() -> approveAfter(gate,b.bookingId()));var two=pool.submit(() -> approveAfter(gate,branch));gate.countDown();
            assertThat(one.get(15,TimeUnit.SECONDS)).isFalse();assertThat(two.get(15,TimeUnit.SECONDS)).isFalse();
            assertThat(state(a.bookingId())).isEqualTo("rejected");assertThat(notices(branch)).isZero();assertThat(notices(b.bookingId())).isZero();
            requests.cancel(ec,e,branch,"Remove obsolete branch");bookings.approve(b.bookingId());assertThat(state(b.bookingId())).isEqualTo("approved");
        } finally {pool.shutdownNow();}
    }
    boolean approveAfter(CountDownLatch gate,UUID id) throws Exception {
        gate.await();try {bookings.approve(id);return true;}catch(VenueBookingStateException expected){return false;}
    }
    @Test @DisplayName("VS17 (conflict): cancellation and a sorted two-venue batch finish without deadlock and release all holds atomically")
    void cancellationAgainstBatch() throws Exception { cancellationRace(false); }
    @Test @DisplayName("VS17 (conflict): cancellation and a replacement approval use the same venue lock order")
    void cancellationAgainstApproval() throws Exception { cancellationRace(true); }
    void cancellationRace(boolean approve) throws Exception {
        var ids=new ArrayList<>(List.of(venue(),venue()));ids.sort(Comparator.naturalOrder());UUID low=ids.get(0),high=ids.get(1);
        UUID cancelled=event(),other=event();
        // The repository reads newest first, so legacy cancellation would lock high first.
        var a=requests.submit(ec,cancelled,request(low,null,10));var b=requests.submit(ec,cancelled,request(high,null,10));
        holds.create(a.bookingId(),OffsetDateTime.now().plusDays(1),vs);holds.create(b.bookingId(),OffsetDateTime.now().plusDays(1),vs);
        UUID successor;
        if(approve) {
            var source=requests.submit(ec,other,request(low,null,14));bookings.approve(source.bookingId());
            successor=requests.submit(ec,other,request(high,source.bookingId(),14)).bookingId();
        } else successor=null;
        var locked=new CountDownLatch(1);var proceed=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();var pool=Executors.newFixedThreadPool(2);
        try {
            var competing=pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT event_id FROM events WHERE event_id=? FOR UPDATE",UUID.class,other);
                jdbc.queryForObject("SELECT venue_id FROM venues WHERE venue_id=? FOR UPDATE",UUID.class,low);locked.countDown();
                try {if(!proceed.await(10,TimeUnit.SECONDS))throw new AssertionError("Cancellation did not reach the lock");}catch(InterruptedException ex){throw new RuntimeException(ex);}
                if(approve)bookings.approve(successor);else requests.submitBatch(ec,other,new SubmitVenueBookingRequest.Batch(List.of(request(high,null,14),request(low,null,14))));
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var cancel=pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return lifecycle.cancel(ec,cancelled,"Audit cancellation");
            }));
            int backend=pid.get(10,TimeUnit.SECONDS);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",Boolean.class,backend));
                if(waiting)break;Thread.onSpinWait();
            }
            assertThat(waiting).as("cancellation waits on the lower venue held by its contender").isTrue();proceed.countDown();
            assertThat(competing.get(15,TimeUnit.SECONDS)).isTrue();assertThat(cancel.get(15,TimeUnit.SECONDS).cancelledBookingIds()).containsExactlyInAnyOrder(a.bookingId(),b.bookingId());
            assertThat(state(a.bookingId())).isEqualTo("cancelled");assertThat(state(b.bookingId())).isEqualTo("cancelled");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_holds WHERE booking_id IN (?,?) AND status='active'",Long.class,a.bookingId(),b.bookingId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id=? AND status=?::venue_booking_status",Long.class,other,approve?"approved":"pending")).isEqualTo(approve?1:2);
        } finally {proceed.countDown();pool.shutdownNow();}
    }
    @Test @DisplayName("VS17 (happy): normal approval and hold conversion notify only the current coordinator once")
    void approvalNotifications() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));var b=requests.submit(ec,e,request(venue(),null,10));
        UUID current=jdbc.queryForObject("SELECT user_id FROM users WHERE username='ec2'",UUID.class);
        jdbc.update("UPDATE events SET coordinator_id=? WHERE event_id=?",current,e);
        bookings.approve(a.bookingId());var hold=holds.create(b.bookingId(),OffsetDateTime.now().plusDays(1),vs);bookings.approve(b.bookingId(),hold.holdId());
        assertThatThrownBy(() -> bookings.approve(a.bookingId())).isInstanceOf(VenueBookingStateException.class);
        for(UUID booking:List.of(a.bookingId(),b.bookingId())) {
            assertThat(notices(booking)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT recipient_user_id FROM notifications WHERE venue_booking_id=? AND type::text='venue_booking_approved'",UUID.class,booking)).isEqualTo(current);
            // EC-NEW4 AC9: the message reads as people say it (no internal ids); the booking and event are linked by their columns.
            assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE venue_booking_id=? AND type::text='venue_booking_approved'",String.class,booking)).matches("Booking at .+ was approved[.]");
            assertThat(jdbc.queryForObject("SELECT event_id FROM notifications WHERE venue_booking_id=? AND type::text='venue_booking_approved'",UUID.class,booking)).isEqualTo(e);
        }
    }
    @Test @DisplayName("VS17 (failure): approval rollback retains pending status and creates no durable approval notice")
    void rolledBackApproval() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {bookings.approve(a.bookingId());tx.setRollbackOnly();});
        assertThat(state(a.bookingId())).isEqualTo("pending");assertThat(notices(a.bookingId())).isZero();
    }
    @Test @DisplayName("VS17 (failure): cancellation rollback restores every venue commitment and active hold without history or notice changes")
    void cancellationRollbackIsAtomic() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));var b=requests.submit(ec,e,request(venue(),null,10));
        holds.create(a.bookingId(),OffsetDateTime.now().plusDays(1),vs);holds.create(b.bookingId(),OffsetDateTime.now().plusDays(1),vs);
        long history=jdbc.queryForObject("SELECT count(*) FROM venue_booking_history WHERE booking_id IN (?,?)",Long.class,a.bookingId(),b.bookingId());
        long outbox=jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?",Long.class,e.toString());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {lifecycle.cancel(ec,e,"Cancelled");tx.setRollbackOnly();});
        assertThat(state(a.bookingId())).isEqualTo("pending");assertThat(state(b.bookingId())).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,e)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_holds WHERE booking_id IN (?,?) AND status='active'",Long.class,a.bookingId(),b.bookingId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_history WHERE booking_id IN (?,?)",Long.class,a.bookingId(),b.bookingId())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?",Long.class,e.toString())).isEqualTo(outbox);
    }
    @Test @DisplayName("VS17 (boundary): rejection allows 2000 characters, refuses 2001 and preserves the independent sibling")
    void rejectionBoundaries() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(),null,10));var b=requests.submit(ec,e,request(venue(),null,10));
        assertThatThrownBy(() -> bookings.reject(a.bookingId(),"x".repeat(2001),null,null)).isInstanceOf(InvalidVenueBookingException.class);
        assertThat(state(a.bookingId())).isEqualTo("pending");bookings.reject(a.bookingId(),"x".repeat(2000),null,"y".repeat(2000));
        assertThat(state(a.bookingId())).isEqualTo("rejected");assertThat(state(b.bookingId())).isEqualTo("pending");
    }
    @Test @DisplayName("VS17 (boundary): timed calendar issues match backend setup blocking, informational issues and touching intervals do not block")
    void calendarIssuesMatchAvailability() {
        UUID v=venue();UUID issue=UUID.randomUUID();
        jdbc.update("INSERT INTO venue_operational_issues(issue_id,venue_id,description,affected_from,affected_until,created_by) VALUES (?,?,'Setup restriction',?,?,?)",issue,v,at(9),at(10),vs);
        jdbc.update("INSERT INTO venue_operational_issues(issue_id,venue_id,description,created_by) VALUES (?,?,'Information',?)",UUID.randomUUID(),v,vs);
        var durations=new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(30,0);
        assertThat(availability.schedule(v,at(8),at(12)).operationalIssues()).extracting(i -> i.issueId()).containsExactly(issue);
        assertThat(availability.hasOperationalIssue(v,at(10),at(12),durations)).isTrue();
        assertThat(availability.hasOperationalIssue(v,at(10),at(12),new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(0,0))).isFalse();
    }
}
