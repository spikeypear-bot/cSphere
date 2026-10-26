package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.connect_sphere.testsupport.*;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;
import com.example.connect_sphere.venuebooking.service.*;
import com.example.connect_sphere.venuebooking.dto.*;
import com.example.connect_sphere.venuebooking.hold.VenueHoldService;
import com.example.connect_sphere.venueissue.service.VenueOperationalIssueService;
import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.notification.service.*;

/** Committed fixtures verify cross-role, concurrency, rollback and restart-safe work on real PostgreSQL. */
@Tag("integration") @Tag("VS17") @Tag("AU04") @Tag("VS08") @Tag("VS01") @Tag("VS13")
@Tag("EC08") @Tag("EC03") @Tag("VS-C3") @Tag("EO09") @Tag("EO07")
@Tag("VS03") @Tag("VS04")
@SpringBootTest(properties={"venue.recovery.delay-ms=86400000","notifications.retry-delay-ms=86400000","venue.holds.expiry-delay-ms=86400000"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.Tag("EC-C2")
@org.junit.jupiter.api.Tag("EC10")
class VenueWorkflowRepairTest {
    @Autowired com.example.connect_sphere.safety.ReadinessService venueReadiness;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired VenueBookingService decisions;
    @Autowired VenueBookingRequestService requests;
    @Autowired VenueOperationalIssueService issues;
    @Autowired VenueHoldService holds;
    @Autowired AvailabilityService availability;
    @Autowired VenueReplanQueue replans;
    @Autowired NotificationOutbox outbox;
    @Autowired NotificationService notifications;
    @Autowired EventLifecycleService lifecycle;
    @Autowired PlatformTransactionManager transactions;
    FlowSupport flow;
    UUID actor,coordinator;
    final List<Fixture> fixtures=new ArrayList<>();
    record Fixture(UUID venue,UUID event,UUID booking) {}
    @BeforeEach void setup() { flow=new FlowSupport(mvc,users); actor=flow.idOf("vs1"); coordinator=flow.idOf("ec1"); }
    Fixture fixture(String eventState,String bookingState,int setup) {
        UUID venue=venues.createVenue(new CreateVenueDto("Repair "+UUID.randomUUID(),200,List.of(VenueLayout.classroom),
            "Daily",null,List.of(),List.of(),VenueHoursFixtures.allWeek())).venueId();
        UUID event=UUID.randomUUID(),booking=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,
              accessibility_needs,status,coordinator_id,organisation)
            VALUES (?,'Repair event','Test','2027-04-01T10:00:00+08:00','2027-04-01T12:00:00+08:00',100,'Seating','{}',?::event_status,?,'Acme Pte Ltd')
            """,event,eventState,coordinator);
        jdbc.update("""
            INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime,setup_duration_minutes,booking_notes)
            VALUES (?,?,?,?::venue_booking_status,'2027-04-01T10:00:00+08:00','2027-04-01T12:00:00+08:00',?,'Private internal notes')
            """,booking,venue,event,bookingState,setup);
        var f=new Fixture(venue,event,booking); fixtures.add(f); return f;
    }
    Request period(String start,String end) {
        return new Request(OffsetDateTime.parse("2027-04-01T"+start+":00+08:00"),OffsetDateTime.parse("2027-04-01T"+end+":00+08:00"),"Maintenance",null,null);
    }
    Period record(Fixture f,Request r) {
        var p=availability.preview(f.venue(),r);
        return availability.create(f.venue(),actor,new Request(r.startDateTime(),r.endDateTime(),r.reason(),
            p.affectedBookings().stream().map(Booking::bookingId).toList(),p.previewToken()));
    }
    String eventState(Fixture f) { return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,f.event()); }
    String bookingState(Fixture f) { return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,f.booking()); }
    @AfterEach void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS repair_fail_event ON events"); jdbc.execute("DROP FUNCTION IF EXISTS repair_fail_event()");
        jdbc.execute("DROP TRIGGER IF EXISTS repair_fail_notice ON notifications"); jdbc.execute("DROP FUNCTION IF EXISTS repair_fail_notice()");
        for (var f:fixtures) {
            jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id'=?",f.event().toString());
            jdbc.update("DELETE FROM notifications WHERE event_id=?",f.event());
            jdbc.update("DELETE FROM venue_unavailability_bookings WHERE booking_id IN (SELECT booking_id FROM venue_bookings WHERE event_id=?)",f.event());
            jdbc.update("DELETE FROM venue_booking_holds WHERE booking_id IN (SELECT booking_id FROM venue_bookings WHERE event_id=?)",f.event());
            jdbc.update("DELETE FROM venue_bookings WHERE event_id=?",f.event()); jdbc.update("DELETE FROM events WHERE event_id=?",f.event());
            jdbc.update("DELETE FROM venue_operational_issues WHERE venue_id=?",f.venue());
            jdbc.update("DELETE FROM venue_unavailability WHERE venue_id=?",f.venue());
            jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",f.venue()); jdbc.update("DELETE FROM venues WHERE venue_id=?",f.venue());
        }
    }
    @ParameterizedTest @ValueSource(strings={"ec2","eo5","att1","ts1","ec1"})
    @DisplayName("VS17 AU04 (negative): private staff booking reads reject other roles even when a coordinator is assigned")
    void privateReadsAreStaffOnly(String account) throws Exception {
        var f=fixture("pending","approved",0);
        mvc.perform(get("/api/venue-bookings/"+f.booking()).with(flow.as(account))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venues/"+f.venue()+"/bookings").with(flow.as(account))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venue-bookings/"+f.booking()).with(flow.as("vs2"))).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"pending","awaiting_safety_review","safety_approved","confirmed"})
    @DisplayName("VS17 VS13 EC08 (conflict): timed issues preserve commitments, notify the current EC, and prevent safety progression")
    void issuesInvalidateReadiness(String eventState) {
        var f=fixture(eventState,"approved",30);
        jdbc.update("UPDATE events SET coordinator_id=? WHERE event_id=?",flow.idOf("ec2"),f.event());
        issues.create(f.venue(),actor,new CreateVenueOperationalIssueRequest("Setup affected",period("09:40","09:50").startDateTime(),period("09:40","09:50").endDateTime()));
        assertThat(eventState(f)).isEqualTo("pending"); assertThat(bookingState(f)).isEqualTo("approved");
        var readiness=lifecycle.readiness(flow.idOf("ec2"),f.event());
        assertThat(readiness.canSendForReview()).isFalse(); assertThat(readiness.canConfirm()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND recipient_user_id=? AND type='venue_unavailable'",Integer.class,f.event(),flow.idOf("ec2"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND recipient_user_id=?",Integer.class,f.event(),coordinator)).isZero();
    }
    @Test @DisplayName("VS17 VS13 (boundary): untimed and touching issues leave existing arrangements ready")
    void nonBlockingIssuesDoNotReplan() {
        var f=fixture("pending","approved",0);
        for(var item:venueReadiness.forSafetyOfficer(f.event()).items()) venueReadiness.updateItem(flow.idOf("so1"),f.event(),item.itemKey(),new com.example.connect_sphere.safety.UpdateReadinessItemRequest(true,"Verified"));
        jdbc.update("UPDATE events SET status='safety_approved' WHERE event_id=?",f.event());
        entityManager.clear();
        issues.create(f.venue(),actor,new CreateVenueOperationalIssueRequest("Information only",null,null));
        issues.create(f.venue(),actor,new CreateVenueOperationalIssueRequest("After event",period("12:00","13:00").startDateTime(),period("12:00","13:00").endDateTime()));
        assertThat(eventState(f)).isEqualTo("safety_approved"); assertThat(availability.affected(f.booking())).isFalse();
        assertThat(lifecycle.readiness(coordinator,f.event()).canConfirm()).isTrue();
    }
    @Test @DisplayName("VS17 VS01 (boundary): reducing a buffer resolves live impact while retaining its historical link")
    void timingReductionResolvesCurrentImpact() {
        var f=fixture("pending","approved",30); record(f,period("09:40","09:50"));
        assertThat(availability.affected(f.booking())).isTrue();
        decisions.updateTiming(f.booking(),actor,new UpdateBookingTimingRequest(java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,"No setup required",30,0));
        assertThat(availability.affected(f.booking())).isFalse(); assertThat(bookingState(f)).isEqualTo("approved");
        assertThat(lifecycle.readiness(coordinator,f.event()).canSendForReview()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_unavailability_bookings WHERE booking_id=?",Integer.class,f.booking())).isEqualTo(1);
    }
    @Test @DisplayName("VS17 VS01 (happy): release affects only its period and preserves booking statuses and history")
    void releasePreservesHistoryAndOtherPeriods() throws Exception {
        var f=fixture("pending","approved",0); var first=record(f,period("10:00","10:30")); var second=record(f,period("11:00","11:30"));
        mvc.perform(patch("/api/venues/"+f.venue()+"/unavailability/"+first.unavailabilityId()+"/release").with(flow.as("ec1"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Resolved\"}")).andExpect(status().isForbidden());
        availability.release(f.venue(),first.unavailabilityId(),actor,new Release("Maintenance complete"));
        assertThat(availability.list(f.venue())).extracting(Period::unavailabilityId).containsExactly(second.unavailabilityId());
        assertThat(availability.affected(f.booking())).isTrue(); assertThat(bookingState(f)).isEqualTo("approved");
        availability.release(f.venue(),second.unavailabilityId(),actor,new Release("Reopened"));
        assertThat(availability.affected(f.booking())).isFalse(); assertThat(availability.list(f.venue())).isEmpty();
        assertThat(availability.history(f.venue())).hasSize(2).allMatch(p -> p.releasedAt()!=null && p.releaseReason()!=null);
        assertThatThrownBy(() -> availability.release(f.venue(),first.unavailabilityId(),actor,new Release("Retry"))).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(() -> availability.release(f.venue(),first.unavailabilityId(),actor,new Release(" "))).isInstanceOf(InvalidVenueBookingException.class);
        assertThat(bookingState(f)).isEqualTo("approved");
    }
    @ParameterizedTest @ValueSource(strings={"pending","approved","attention","rejected","cancelled"})
    @DisplayName("VS17 EC10 (happy): same-venue rescheduling preserves its source until approval")
    void sameVenueReplacementKeepsItsOriginal(String state) {
        var f=fixture("pending",state,0);
        if (Set.of("pending","approved").contains(state)) record(f,period("10:00","10:30"));
        var replacement=requests.submit(coordinator,f.event(),new SubmitVenueBookingRequest(f.venue(),null,null,f.booking(),period("13:00","15:00").startDateTime(),period("13:00","15:00").endDateTime()));
        assertThat(replacement.status().name()).isEqualTo("pending");
        assertThat(replacement.replacesBookingId()).isEqualTo(f.booking());
        assertThat(bookingState(f)).isEqualTo(state);
    }
    @Test @DisplayName("VS17 VS01 EC08 (failure): failed replanning remains durable and retries once after recovery")
    void replanningRecoversWithoutRepeatingUnavailability() {
        var f=fixture("confirmed","approved",0);
        jdbc.execute("CREATE FUNCTION repair_fail_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Simulated event failure'; END $$");
        jdbc.execute("CREATE TRIGGER repair_fail_event BEFORE UPDATE ON events FOR EACH ROW EXECUTE FUNCTION repair_fail_event()");
        var p=record(f,period("10:00","11:00"));
        assertThat(eventState(f)).isEqualTo("confirmed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_replan_tasks WHERE event_id=? AND completed_at IS NULL",Integer.class,f.event())).isEqualTo(1);
        jdbc.execute("DROP TRIGGER repair_fail_event ON events"); jdbc.execute("DROP FUNCTION repair_fail_event()");
        replans.retry(); replans.retry();
        assertThat(eventState(f)).isEqualTo("pending"); assertThat(bookingState(f)).isEqualTo("approved");
        assertThat(availability.list(f.venue())).extracting(Period::unavailabilityId).containsExactly(p.unavailabilityId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_replan_tasks WHERE event_id=? AND completed_at IS NULL",Integer.class,f.event())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND type='event_returned_to_planning'",Integer.class,f.event())).isEqualTo(1);
    }
    @Test @DisplayName("VS17 EO09 (failure): notification insert failure retains committed delivery work and retries without duplication")
    void notificationDeliveryRecoversAfterFailure() {
        var f=fixture("pending","pending",0);
        jdbc.execute("CREATE FUNCTION repair_fail_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Simulated notice failure'; END $$");
        jdbc.execute("CREATE TRIGGER repair_fail_notice BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION repair_fail_notice()");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> notifications.createStatusChangeNotification(coordinator,null,f.event(),"Repair event","approved",null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=? AND delivered_at IS NULL",Integer.class,f.event().toString())).isEqualTo(1);
        jdbc.execute("DROP TRIGGER repair_fail_notice ON notifications"); jdbc.execute("DROP FUNCTION repair_fail_notice()");
        outbox.retry(); outbox.retry();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Integer.class,f.event())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=? AND delivered_at IS NULL",Integer.class,f.event().toString())).isZero();
    }
    @Test @DisplayName("VS17 EO09 (failure): rolled-back actions leave neither notices nor queued delivery")
    void rolledBackActionDoesNotNotify() {
        var f=fixture("pending","pending",0);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            notifications.createStatusChangeNotification(coordinator,null,f.event(),"Repair event","approved",null); tx.setRollbackOnly();
        });
        outbox.retry();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?",Integer.class,f.event().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Integer.class,f.event())).isZero();
    }
    @Test @DisplayName("VS17 VS08 VS13 (conflict): an issue waits for approval's venue lock then invalidates the commitment")
    void issueAndApprovalSerialize() throws Exception {
        var f=fixture("pending","pending",0); var started=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                decisions.approve(f.booking());
                var issue=pool.submit(() -> { started.countDown(); return issues.create(f.venue(),actor,new CreateVenueOperationalIssueRequest("Maintenance",period("10:00","11:00").startDateTime(),period("10:00","11:00").endDateTime())); });
                try { assertThat(started.await(3,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> issue.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                catch (InterruptedException ex) { throw new RuntimeException(ex); }
            });
            pool.shutdown(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(bookingState(f)).isEqualTo("approved"); assertThat(availability.affected(f.booking())).isTrue();
        assertThat(lifecycle.readiness(coordinator,f.event()).canSendForReview()).isFalse();
    }
    @Test @DisplayName("VS17 VS08 VS13 VS-C3 (conflict): an already committed issue prevents both approval and tentative holds")
    void issuePreventsNewCommitments() {
        var f=fixture("pending","pending",0);
        issues.create(f.venue(),actor,new CreateVenueOperationalIssueRequest("Maintenance",period("10:00","11:00").startDateTime(),period("10:00","11:00").endDateTime()));
        assertThatThrownBy(() -> decisions.approve(f.booking())).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(() -> holds.create(f.booking(),OffsetDateTime.now().plusHours(1),actor)).isInstanceOf(VenueBookingStateException.class);
        assertThat(bookingState(f)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_holds WHERE booking_id=?",Integer.class,f.booking())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"cancelled","completed"})
    @DisplayName("VS17 VS03 VS04 (negative): stale pending rows on final events cannot be decided")
    void finalEventPendingRowsCannotBeDecided(String state) {
        var f=fixture(state,"pending",0);
        assertThatThrownBy(() -> decisions.approve(f.booking())).isInstanceOf(VenueBookingStateException.class);
        assertThatThrownBy(() -> decisions.reject(f.booking(),"Not available",null,null)).isInstanceOf(VenueBookingStateException.class);
        assertThat(bookingState(f)).isEqualTo("pending");
    }
}
