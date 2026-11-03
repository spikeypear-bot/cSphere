package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.connect_sphere.changerequest.service.ChangeRequestService;
import com.example.connect_sphere.changerequest.service.VenueRequirementReviewService;
import java.util.*;
import java.util.stream.IntStream;
import java.time.LocalTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import jakarta.persistence.EntityManager;

@Tag("VS17") @Tag("integration") @SpringBootTest @AutoConfigureMockMvc
class VenueReviewWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ChangeRequestService changes;
    @Autowired VenueRequirementReviewService reviews;
    private FlowSupport flow;
    private UUID eventId, requestId, changeId, booking, otherBooking, review, otherReview, venue;
    @BeforeEach void setup() { flow=new FlowSupport(mvc,users); }
    private void fixture() throws Exception {
        String name="VS17 Review " + UUID.randomUUID();
        eventId=flow.approvedEvent("eo1","ec1",FlowSupport.requestBody(name,100,"none")); em.flush();
        requestId=jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id=?",UUID.class,eventId);
        List<UUID> ids=new ArrayList<>();
        for (int i=0;i<2;i++) {
            UUID v=venues.createVenue(new CreateVenueDto("VS17 Hall " + UUID.randomUUID(),500,List.of(VenueLayout.theatre),"Daily",null,List.of(),List.of(),
                IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIN,LocalTime.of(23,59))).toList())).venueId(); em.flush();
            UUID id=UUID.randomUUID(); ids.add(id); if(i==0) venue=v;
            jdbc.update("INSERT INTO venue_bookings (booking_id,event_id,venue_id,status,start_datetime,end_datetime,setup_duration_minutes,turnaround_duration_minutes) VALUES (?,?,?,'approved','2027-03-10 09:00+08','2027-03-10 12:00+08',30,45)",id,eventId,v);
        }
        booking=ids.get(0); otherBooking=ids.get(1);
        String response=mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\""+requestId+"\",\"reason\":\"More guests\",\"details\":"+FlowSupport.requestBody(name,200,"none")+"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        changeId=UUID.fromString(flow.read(response).get("changeRequestId").asString());
        changes.approve(new ChangeRequestService.Caller(UserRole.ec,flow.idOf("ec1"),"ConnectSphere"),changeId,List.of()); em.flush();
        review=jdbc.queryForObject("SELECT review_id FROM venue_requirement_reviews WHERE booking_id=?",UUID.class,booking);
        otherReview=jdbc.queryForObject("SELECT review_id FROM venue_requirement_reviews WHERE booking_id=?",UUID.class,otherBooking);
    }
    private String path(UUID b, UUID r) { return "/api/venue-bookings/"+b+"/requirement-reviews/"+r+"/decision"; }
    private ResultActions decide(String actor, String outcome, String note) throws Exception {
        return mvc.perform(post(path(booking,review)).with(flow.as(actor)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"outcome\":\""+outcome+"\",\"note\":\""+note+"\"}"));
    }
    private String state(UUID id) { em.flush(); return jdbc.queryForObject("SELECT status FROM venue_requirement_reviews WHERE review_id=?",String.class,id); }
    @Test @Transactional @DisplayName("VS17 (happy): reading an alert leaves review required; submitting suitable preserves reservations and informs the coordinator")
    void suitableAndReadIndependence() throws Exception {
        fixture();
        var before=jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking);
        var eventBefore=jdbc.queryForObject("SELECT to_jsonb(e)::text FROM events e WHERE event_id=?",String.class,eventId);
        UUID notice=jdbc.queryForObject("SELECT notification_id FROM notifications WHERE requirement_review_id=? AND recipient_user_id=?",UUID.class,review,flow.idOf("vs1"));
        mvc.perform(post("/api/notifications/"+notice+"/read").with(flow.as("vs1"))).andExpect(status().isOk());
        mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].review.status").value("pending"))
            .andExpect(jsonPath("$[0].canReview").value(true)).andExpect(jsonPath("$[0].changes[0].before").value(100))
            .andExpect(jsonPath("$[0].changes[0].after").value(200));
        decide("vs1","suitable","").andExpect(status().isOk()).andExpect(jsonPath("$.review.status").value("suitable"));
        assertThat(state(otherReview)).isEqualTo("pending");
        assertThat(jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM events e WHERE event_id=?",String.class,eventId)).isEqualTo(eventBefore);
        mvc.perform(get("/api/events/"+eventId+"/venue-reviews").with(flow.as("ec1"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.review.reviewId == '"+review+"')].review.status").value(org.hamcrest.Matchers.contains("suitable")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE requirement_review_id=? AND type::text='venue_requirement_reviewed' AND recipient_user_id=?",Integer.class,review,flow.idOf("ec1"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE event_id=? AND activity_type='venue_requirements_reviewed'",Integer.class,eventId)).isEqualTo(1);
    }
    @Test @Transactional @DisplayName("VS17 (happy): coordinator action requires a comment and notifies the current assignee after reassignment")
    void actionAndReassignment() throws Exception {
        fixture();
        decide("vs1","action_required","   ").andExpect(status().isUnprocessableEntity());
        assertThat(state(review)).isEqualTo("pending");
        jdbc.update("UPDATE events SET coordinator_id=? WHERE event_id=?",flow.idOf("ec2"),eventId); em.clear();
        decide("vs1","action_required","Arrange accessible seating").andExpect(status().isOk());
        assertThat(state(review)).isEqualTo("action_required");
        mvc.perform(get("/api/events/"+eventId+"/venue-reviews").with(flow.as("ec1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/events/"+eventId+"/venue-reviews").with(flow.as("ec2"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT recipient_user_id FROM notifications WHERE requirement_review_id=? AND type::text='venue_requirement_reviewed'",UUID.class,review)).isEqualTo(flow.idOf("ec2"));
        assertThat(jdbc.queryForObject("SELECT coordinator_id FROM venue_requirement_reviews WHERE review_id=?",UUID.class,review)).isEqualTo(flow.idOf("ec1"));
    }
    @Test @Transactional @DisplayName("VS17 (negative): only staff can read and submit booking reviews; only the assigned coordinator can read event outcomes")
    void permissions() throws Exception {
        fixture();
        for(String user:List.of("eo1","ec1","ec2","ecl1","so1")) {
            mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as(user))).andExpect(status().isForbidden());
            decide(user,"suitable","").andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/events/"+eventId+"/venue-reviews").with(flow.as("ec2"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/events/"+eventId+"/venue-reviews").with(flow.as("eo1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews")).andExpect(status().isUnauthorized());
        mvc.perform(post(path(otherBooking,review)).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"suitable\"}"))
            .andExpect(status().isNotFound());
        assertThat(state(review)).isEqualTo("pending"); assertThat(state(otherReview)).isEqualTo("pending");
    }
    @Test @Transactional @DisplayName("VS17 (conflict): a decided review cannot be overwritten and duplicate submission creates no second notification")
    void duplicateDecision() throws Exception {
        fixture(); decide("vs1","suitable","").andExpect(status().isOk());
        decide("vs2","action_required","New outcome").andExpect(status().isConflict());
        assertThat(state(review)).isEqualTo("suitable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE requirement_review_id=? AND type::text='venue_requirement_reviewed'",Integer.class,review)).isEqualTo(1);
    }
    @Test @Transactional @DisplayName("VS17 (negative): invalid outcomes and oversized comments do not complete a review")
    void validation() throws Exception {
        fixture(); decide("vs1","approved","").andExpect(status().isUnprocessableEntity());
        decide("vs1","suitable","x".repeat(2001)).andExpect(status().isUnprocessableEntity());
        assertThat(state(review)).isEqualTo("pending");
        decide("vs1","action_required","x".repeat(2000)).andExpect(status().isOk());
    }
    @Test @Transactional @DisplayName("VS17 (negative): cancelled bookings retain history and cannot be reviewed")
    void terminalBooking() throws Exception {
        fixture(); jdbc.update("UPDATE venue_bookings SET status='cancelled' WHERE booking_id=?",booking); em.clear();
        mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].canReview").value(false));
        decide("vs1","suitable","").andExpect(status().isConflict()); assertThat(state(review)).isEqualTo("pending");
    }
    @Test @Transactional @DisplayName("VS17 (boundary): own reservation is excluded while another same-event booking is checked at buffered times")
    void liveChecks() throws Exception {
        fixture();
        String initial=mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(initial).doesNotContain(":clash");
        jdbc.update("UPDATE venue_bookings SET venue_id=? WHERE booking_id=?",venue,otherBooking); em.clear();
        String clashes=mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(clashes).contains(":clash","including setup and turnaround");
    }
    @Test @Transactional @DisplayName("VS17 (conflict): historical requirement snapshots cannot be changed in the database")
    void immutableHistory() throws Exception {
        fixture(); assertThatThrownBy(() -> jdbc.update("UPDATE venue_requirement_reviews SET event_name='rewritten' WHERE review_id=?",review))
            .hasMessageContaining("history cannot be changed");
    }
    @Test @DisplayName("VS17 (failure): notification failure rolls back review outcome and audit without publishing an outcome")
    void rollbackDecision() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try { fixture(); } catch(Exception e) { throw new RuntimeException(e); }
            jdbc.execute("CREATE FUNCTION vs17_refuse_outcome() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type::text='venue_requirement_reviewed' THEN RAISE EXCEPTION 'VS17 outcome storage failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER vs17_refuse_outcome BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION vs17_refuse_outcome()");
            reviews.decide(booking,review,flow.idOf("vs1"),new VenueRequirementReviewService.Decision("suitable",null));
        })).hasStackTraceContaining("VS17 outcome storage failure");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE event_id=?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id=?",Integer.class,eventId)).isZero();
    }

    @Test @Transactional @DisplayName("VS17 (conflict): staff cannot mark a booking suitable when live capacity checks fail")
    void liveUnsuitable() throws Exception {
        fixture(); jdbc.update("UPDATE venues SET venue_capacity=100 WHERE venue_id=?",venue); em.clear();
        decide("vs1","suitable","").andExpect(status().isConflict());
        assertThat(state(review)).isEqualTo("pending");
        decide("vs1","action_required","Find a hall for 200 guests").andExpect(status().isOk());
    }

    private ResultActions decideOther(String body) throws Exception {
        return mvc.perform(post(path(otherBooking,otherReview)).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test @Transactional @DisplayName("VS17 (conflict): a review waits while the booking needs reassessment, and is refused once the event is cancelled, completed or over; nothing is recorded")
    void onlyAnOpenBookingIsReviewed() throws Exception {
        fixture();
        jdbc.update("UPDATE venue_bookings SET status='attention' WHERE booking_id=?",booking); em.clear();
        mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].outcomeCurrent").value(true)).andExpect(jsonPath("$[0].canReview").value(false));
        decide("vs1","suitable","").andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Resolve the booking assessment before recording this requirement review."));
        for (String change:List.of("status='cancelled'","status='completed'","start_datetime=now()-interval '2 hours', end_datetime=now()-interval '1 hour'")) {
            jdbc.update("UPDATE events SET status='pending',start_datetime='2027-03-10 09:00+08',end_datetime='2027-03-10 12:00+08' WHERE event_id=?",eventId);
            jdbc.update("UPDATE events SET "+change+" WHERE event_id=?",eventId); em.clear();
            decideOther("{\"outcome\":\"suitable\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only active pending or approved bookings can be reviewed. Refresh the booking details."));
        }
        assertThat(state(review)).isEqualTo("pending"); assertThat(state(otherReview)).isEqualTo("pending");
    }

    @Test @Transactional @DisplayName("VS17 (negative): a decision without an outcome is refused and nothing is recorded")
    void anOutcomeIsNeeded() throws Exception {
        fixture();
        decideOther("{}").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value("Choose remains suitable or coordinator action required."));
        assertThat(state(otherReview)).isEqualTo("pending");
    }

    @Test @Transactional @DisplayName("VS17 (conflict): moving the booking's start or end, changing its turnaround, or a later timing edit supersedes the open review")
    void movedBookingSupersedesTheReview() throws Exception {
        fixture();
        for (String change:List.of("start_datetime='2027-03-10 09:30+08'","end_datetime='2027-03-10 11:30+08'","turnaround_duration_minutes=60","timing_updated_at=now()+interval '1 day'")) {
            jdbc.update("UPDATE venue_bookings SET start_datetime='2027-03-10 09:00+08',end_datetime='2027-03-10 12:00+08',turnaround_duration_minutes=45,timing_updated_at=NULL WHERE booking_id=?",booking);
            jdbc.update("UPDATE venue_bookings SET "+change+" WHERE booking_id=?",booking); em.clear();
            mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcomeCurrent").value(false)).andExpect(jsonPath("$[0].canReview").value(false));
            decide("vs1","suitable","").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This requirement review has been superseded. Refresh to review the current requirements."));
        }
        assertThat(state(review)).isEqualTo("pending");
    }

    @Test @Transactional @DisplayName("VS17 (boundary): a legacy booking without its own times is reviewed at the event's times; a recorded outcome stays current after an earlier timing edit, and an event without a coordinator names none")
    void legacyBookingIsReviewedAtTheEventsTimes() throws Exception {
        fixture();
        jdbc.update("UPDATE venue_bookings SET start_datetime=NULL,end_datetime=NULL,timing_updated_at='2020-01-01 00:00+00' WHERE booking_id=?",otherBooking); em.clear();
        mvc.perform(get("/api/venue-bookings/"+otherBooking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].canReview").value(true)).andExpect(jsonPath("$[0].outcomeCurrent").value(true));
        decideOther("{\"outcome\":\"suitable\"}").andExpect(status().isOk());
        assertThat(state(otherReview)).isEqualTo("suitable");
        jdbc.update("UPDATE venue_bookings SET timing_updated_at='2020-01-01 00:00+00' WHERE booking_id=?",otherBooking);
        jdbc.update("UPDATE events SET coordinator_id=NULL WHERE event_id=?",eventId); em.clear();
        var view=reviews.forBooking(otherBooking).getFirst();
        assertThat(view.outcomeCurrent()).isTrue(); assertThat(view.currentCoordinatorName()).isNull();
        assertThat(view.reservedStart().toInstant()).isEqualTo(java.time.OffsetDateTime.parse("2027-03-10T09:00:00+08:00").toInstant());
        // A timing edit after the outcome was recorded makes it historical.
        jdbc.update("UPDATE venue_bookings SET timing_updated_at=now()+interval '1 day' WHERE booking_id=?",otherBooking); em.clear();
        assertThat(reviews.forBooking(otherBooking).getFirst().outcomeCurrent()).isFalse();
    }
}
