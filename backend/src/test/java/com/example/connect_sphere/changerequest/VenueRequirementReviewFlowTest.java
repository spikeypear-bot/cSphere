package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.stream.IntStream;
import java.time.LocalTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import jakarta.persistence.EntityManager;

@Tag("VS17") @Tag("integration")
@SpringBootTest @AutoConfigureMockMvc
class VenueRequirementReviewFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venues;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ChangeRequestService changes;
    @Autowired VenueRequirementChangeService recorder;
    @Autowired ChangeRequestRepository changeRows;
    @Autowired com.example.connect_sphere.event.repository.EventRepository events;
    private FlowSupport flow;
    private UUID eventId, requestId, changeId;
    private String name;
    private List<UUID> bookingIds;
    @BeforeEach void setup() { flow = new FlowSupport(mvc, users); name = "VS17 " + UUID.randomUUID(); }

    private void fixture() throws Exception {
        eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 100, "none"));
        em.flush();
        requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
        bookingIds = new ArrayList<>();
        for (String state : List.of("pending", "approved", "cancelled", "rejected", "changed")) {
            UUID venue = venues.createVenue(new CreateVenueDto("VS17 " + UUID.randomUUID(), 500,
                    List.of(VenueLayout.theatre), "Daily", null, List.of(), List.of(),
                    IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d, LocalTime.MIN, LocalTime.of(23,59))).toList())).venueId();
            em.flush();
            UUID booking = UUID.randomUUID(); bookingIds.add(booking);
            jdbc.update("INSERT INTO venue_bookings (booking_id,event_id,venue_id,status,start_datetime,end_datetime) "
                    + "VALUES (?,?,?,?::venue_booking_status,'2027-03-10 09:00+08','2027-03-10 12:00+08')", booking,eventId,venue,state);
        }
        String response = mvc.perform(post("/api/change-requests").with(flow.as("eo1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + requestId
                + "\",\"reason\":\"More guests\",\"details\":" + FlowSupport.requestBody(name, 200, "none") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        changeId = UUID.fromString(flow.read(response).get("changeRequestId").asString());
    }
    private void approve() {
        changes.approve(new ChangeRequestService.Caller(com.example.connect_sphere.user.entity.UserRole.ec,
                flow.idOf("ec1"), "ConnectSphere"), changeId, List.of());
        em.flush();
    }
    @Test @Transactional @DisplayName("VS17 (happy): approval creates two reviews and staff-only booking alerts, with historical values and independent read state")
    void approvalAndAudit() throws Exception {
        fixture();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?", Integer.class,eventId)).isZero();
        approve();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?", Integer.class,eventId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT booking_id FROM venue_requirement_reviews WHERE event_id = ?", UUID.class,eventId))
                .containsExactlyInAnyOrder(bookingIds.get(0), bookingIds.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ? AND previous_requirements->>'expectedAttendance' = '100' AND new_requirements->>'expectedAttendance' = '200' AND coordinator_id = ? AND approved_by = ?", Integer.class,eventId,flow.idOf("ec1"),flow.idOf("ec1"))).isEqualTo(2);
        int staff = users.findByRole(com.example.connect_sphere.user.entity.UserRole.vs).size();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ? AND type::text = 'venue_requirement_changed'",Integer.class,eventId)).isEqualTo(2*staff);
        String notices = mvc.perform(get("/api/notifications").with(flow.as("vs1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(notices).contains("/venue-staff/bookings/"+bookingIds.get(0), "100 → 200", "Review required", "ec1");
        UUID noticeId = jdbc.queryForObject("SELECT notification_id FROM notifications WHERE event_id = ? AND requirement_review_id IS NOT NULL AND recipient_user_id = ? LIMIT 1",UUID.class,eventId,flow.idOf("vs1"));
        mvc.perform(post("/api/notifications/"+noticeId+"/read").with(flow.as("vs1"))).andExpect(status().isOk());
        mvc.perform(post("/api/notifications/"+noticeId+"/read").with(flow.as("eo1"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ? AND status = 'pending'",Integer.class,eventId)).isEqualTo(2);
        var event = events.findById(eventId).orElseThrow();
        recorder.approved(changeRows.findById(changeId).orElseThrow(),event,event.getStartDatetime(),event.getEndDatetime());
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?",Integer.class,eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ? AND requirement_review_id IS NOT NULL",Integer.class,eventId)).isEqualTo(2*staff);
        jdbc.update("UPDATE events SET expected_attendance = 300 WHERE event_id = ?",eventId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ? AND new_requirements->>'expectedAttendance' = '200'",Integer.class,eventId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status::text FROM venue_bookings WHERE event_id = ?",String.class,eventId))
                .containsExactlyInAnyOrder("pending","approved","cancelled","rejected","changed");
    }
    @Test @DisplayName("VS17 (failure): a rolled-back approval leaves no review or notification visible after the transaction")
    void rollback() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try { fixture(); approve(); } catch(Exception e) { throw new RuntimeException(e); }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?",Integer.class,eventId)).isEqualTo(2);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",Integer.class,eventId)).isZero();
    }

    @Test @DisplayName("VS17 (failure): failed notification storage rolls back approval, review snapshots and all alerts")
    void notificationInsertFailure() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try { fixture(); } catch (Exception e) { throw new RuntimeException(e); }
            jdbc.execute("CREATE FUNCTION vs17_refuse_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type::text = 'venue_requirement_changed' THEN RAISE EXCEPTION 'VS17 storage failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER vs17_refuse_notice BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION vs17_refuse_notice()");
            approve();
        })).hasStackTraceContaining("VS17 storage failure");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE event_id = ?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_change_requests WHERE change_request_id = ?",Integer.class,changeId)).isZero();
    }
    @Test @Transactional @DisplayName("VS17 (conflict): the database rejects a second review for the same approved change and booking")
    void databaseDeduplication() throws Exception {
        fixture(); approve();
        String columns = "change_request_id,booking_id,event_id,venue_id,approved_at,approved_by,reserved_start,reserved_end,setup_duration_minutes,turnaround_duration_minutes,previous_requirements,new_requirements,event_name,venue_address";
        assertThatThrownBy(() -> jdbc.update("INSERT INTO venue_requirement_reviews (review_id,"+columns+") SELECT ?,"+columns+" FROM venue_requirement_reviews WHERE event_id = ? LIMIT 1",UUID.randomUUID(),eventId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                .hasMessageContaining("change_request_id, booking_id");
    }
}
