package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.dto.*;
import com.example.connect_sphere.venuebooking.service.*;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.safety.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
@Tag("integration") @Tag("VS17") @Tag("EC-C2") @Tag("EC08") @Tag("EC10") @Tag("SO-C2") @Tag("VS04") @Tag("VS25")
class MultipleVenueRequirementsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired VenueService venues;
    @Autowired VenueBookingRequestService requests;
    @Autowired VenueBookingService bookings;
    @Autowired EventLifecycleService lifecycle;
    @Autowired ReadinessService safety;
    @Autowired VenueSafetyChecklist checklist;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;
    UUID ec,vs,so; FlowSupport flow;
    @BeforeEach void setup() { flow=new FlowSupport(mvc,users);ec=flow.idOf("ec1");vs=flow.idOf("vs1");so=flow.idOf("so1"); }
    OffsetDateTime at(int hour) { return OffsetDateTime.parse("2030-04-01T%02d:00:00+08:00".formatted(hour)); }
    UUID venue(int capacity) {
        return venues.createVenue(new CreateVenueDto("Multi "+UUID.randomUUID(),capacity,List.of(VenueLayout.classroom),"Daily",null,List.of(),List.of(),
            java.util.stream.IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIDNIGHT,LocalTime.of(23,59))).toList(),BigDecimal.ZERO,BigDecimal.ZERO)).venueId();
    }
    UUID event() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_requirements,accessibility_needs,status,coordinator_id,organisation) VALUES (?,'Multi venue','Test',?,?,300,'Conference','{}','pending',?,'Acme Pte Ltd')",id,at(8),at(18),ec);
        return id;
    }
    SubmitVenueBookingRequest request(UUID venue,UUID source,int people,int start,int end) {
        return new SubmitVenueBookingRequest(venue,null,null,source,at(start),at(end),BigDecimal.ZERO,BigDecimal.ZERO,null,
            new BookingRequirements(people,List.of(),List.of(),"Breakout session"));
    }
    String bookingState(UUID booking) { return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,booking); }
    String snapshot(UUID booking) { return jdbc.queryForObject("SELECT to_jsonb(b)::text FROM venue_bookings b WHERE booking_id=?",String.class,booking); }
    void tickAll(UUID event) { for(var item:safety.forSafetyOfficer(event).items()) safety.updateItem(so,event,item.itemKey(),new UpdateReadinessItemRequest(true,"Verified this arrangement")); }

    @Test @DisplayName("EC-C2 (happy): auditorium and smaller breakout independently satisfy their saved requirements")
    void independentRequirementsAndApproval() {
        UUID e=event(); var result=requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(venue(350),null,300,9,17),request(venue(100),null,75,10,12))));
        assertThat(result).hasSize(2); assertThat(result.get(1).requirements().expectedAttendance()).isEqualTo(75);
        bookings.approve(result.get(0).bookingId()); assertThat(bookingState(result.get(1).bookingId())).isEqualTo("pending");
        assertThat(lifecycle.readiness(ec,e).canSendForReview()).isFalse();
        bookings.approve(result.get(1).bookingId()); assertThat(lifecycle.readiness(ec,e).canSendForReview()).isTrue();
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM events WHERE event_id=?",Integer.class,e)).isEqualTo(300);
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("EC-C2 (failure): a late invalid room rolls back bookings, requirements, history and notification work")
    void batchRollbackIncludesSideEffects() {
        UUID e=event(),first=venue(350),second=venue(20);
        try {
            long outbox=jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?",Long.class,e.toString());
            assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(first,null,300,9,17),request(second,null,75,10,12))))).isInstanceOf(InvalidVenueBookingException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id=?",Long.class,e)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_requirements WHERE event_id=?",Long.class,e)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_booking_history WHERE booking->>'event_id'=?",Long.class,e.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?",Long.class,e.toString())).isEqualTo(outbox);
        } finally {
            jdbc.update("DELETE FROM events WHERE event_id=?",e);
            jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id IN (?,?)",first,second);
            jdbc.update("DELETE FROM venues WHERE venue_id IN (?,?)",first,second);
        }
    }
    @Test @DisplayName("EC08 VS04 (negative): rejecting a required room leaves readiness blocked until explicit withdrawal")
    void rejectedRequirementCannotDisappear() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(350),null,300,9,17));var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
        bookings.approve(a.bookingId());String before=snapshot(a.bookingId());bookings.reject(b.bookingId(),"Room unavailable",null,null);
        assertThat(lifecycle.readiness(ec,e).canSendForReview()).isFalse();
        requests.withdrawRequirement(ec,e,b.bookingId(),"Breakout session removed");
        assertThat(lifecycle.readiness(ec,e).canSendForReview()).isTrue(); assertThat(snapshot(a.bookingId())).isEqualTo(before);
    }
    @Test @DisplayName("EC08 EC10 (happy): approved replacement fulfils the rejected requirement while keeping history")
    void replacementFulfilsRequirement() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(old.bookingId(),"Unavailable",null,null);
        var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));
        assertThat(next.requirementId()).isEqualTo(old.requirementId());bookings.approve(next.bookingId());
        assertThat(bookingState(old.bookingId())).isEqualTo("rejected");assertThat(lifecycle.readiness(ec,e).canSendForReview()).isTrue();
    }
    @Test @DisplayName("EC10 VS25 (happy): same-venue rescheduling retains the original until atomic approval")
    void sameVenueReplacementRetainsReservation() {
        UUID e=event(),v=venue(100);var old=requests.submit(ec,e,request(v,null,75,10,12));bookings.approve(old.bookingId());
        var next=requests.submit(ec,e,request(v,old.bookingId(),75,11,13));
        assertThat(bookingState(old.bookingId())).isEqualTo("approved");bookings.approve(next.bookingId());
        assertThat(bookingState(old.bookingId())).isEqualTo("changed");assertThat(bookingState(next.bookingId())).isEqualTo("approved");
    }
    @Test @DisplayName("SO-C2 (conflict): a changed room needs fresh checks while another room's attestation stays valid")
    void safetyEvidenceIsPerBooking() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(350),null,300,9,17));var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
        bookings.approve(a.bookingId());bookings.approve(b.bookingId());tickAll(e);assertThat(checklist.complete(e)).isTrue();
        bookings.updateTiming(a.bookingId(),vs,new UpdateBookingTimingRequest(BigDecimal.TEN,BigDecimal.ZERO,"Crew access",0,0));
        var items=safety.forSafetyOfficer(e).items();
        assertThat(items.stream().filter(i -> i.itemKey().equals("venue_safety_"+a.bookingId())).findFirst().orElseThrow().checked()).isFalse();
        assertThat(items.stream().filter(i -> i.itemKey().equals("venue_safety_"+b.bookingId())).findFirst().orElseThrow().checked()).isTrue();
    }
    @Test @DisplayName("SO-C2 EC08 (negative): safety approval rechecks current venue arrangements")
    void safetyApprovalChecksLiveArrangements() {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(b.bookingId());tickAll(e);
        lifecycle.sendForSafetyReview(ec,e);
        jdbc.update("UPDATE venue_bookings SET status='attention' WHERE booking_id=?",b.bookingId());
        assertThatThrownBy(() -> safety.review(so,e,new SafetyApprovalRequest("approve",""))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,e)).isEqualTo("awaiting_safety_review");
    }
    @Test @DisplayName("VS04 (happy): rejection queues one booking-specific notice for the current coordinator")
    void rejectionNotifiesCurrentCoordinator() throws Exception {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
        UUID next=flow.idOf("ec2");jdbc.update("UPDATE events SET coordinator_id=? WHERE event_id=?",next,e); entityManager.clear();
        mvc.perform(patch("/api/venue-bookings/"+b.bookingId()+"/reject").with(flow.as("vs1")).contentType("application/json").content("{\"reason\":\"Maintenance\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'type'='venue_booking_rejected' AND payload->>'venue_booking_id'=? AND payload->>'recipient_user_id'=?",Long.class,b.bookingId().toString(),next.toString())).isEqualTo(1);
        mvc.perform(patch("/api/venue-bookings/"+b.bookingId()+"/reject").with(flow.as("vs1")).contentType("application/json").content("{\"reason\":\"Again\"}")).andExpect(status().isConflict());
    }
    @Test @DisplayName("EC-C2 (cross-cutting): scoped booking history contains independent immutable snapshots")
    void historyIsBookingScoped() throws Exception {
        UUID e=event();var a=requests.submit(ec,e,request(venue(350),null,300,9,17));var b=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(a.bookingId());
        assertThat(bookings.history(a.bookingId())).hasSize(2);assertThat(bookings.history(b.bookingId())).hasSize(1);
        mvc.perform(get("/api/events/"+e+"/venue-bookings/"+a.bookingId()+"/history").with(flow.as("ec2"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venue-bookings/"+a.bookingId()+"/history").with(flow.as("att1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/venue-bookings/"+a.bookingId()+"/history").with(flow.as("vs2"))).andExpect(status().isOk());
    }

    @Test @DisplayName("EC10 VS25 (failure): failed replacement approval preserves the original and sibling reservations")
    void failedReplacementPreservesAllReservations() {
        UUID e=event(),target=venue(100);var old=requests.submit(ec,e,request(venue(100),null,75,10,12));
        var sibling=requests.submit(ec,e,request(venue(350),null,300,9,17));bookings.approve(old.bookingId());bookings.approve(sibling.bookingId());
        var next=requests.submit(ec,e,request(target,old.bookingId(),75,10,12));
        var competitor=requests.submit(ec,event(),request(target,null,75,10,12));bookings.approve(competitor.bookingId());
        String original=snapshot(old.bookingId()),other=snapshot(sibling.bookingId());
        assertThatThrownBy(() -> bookings.approve(next.bookingId())).isInstanceOf(VenueBookingConflictException.class);
        assertThat(snapshot(old.bookingId())).isEqualTo(original);assertThat(snapshot(sibling.bookingId())).isEqualTo(other);
        assertThat(bookingState(next.bookingId())).isEqualTo("pending");
    }
    @Test @DisplayName("EC10 VS25 (happy): cancelling one arrangement preserves siblings and a later request creates a new requirement")
    void cancellationAndRebookingAreIndependent() {
        UUID e=event(),v=venue(100);var old=requests.submit(ec,e,request(v,null,75,10,12));
        var sibling=requests.submit(ec,e,request(venue(350),null,300,9,17));bookings.approve(old.bookingId());bookings.approve(sibling.bookingId());
        String before=snapshot(sibling.bookingId());requests.cancel(ec,e,old.bookingId(),"Session removed");
        assertThat(snapshot(sibling.bookingId())).isEqualTo(before);assertThat(lifecycle.readiness(ec,e).canSendForReview()).isTrue();
        var next=requests.submit(ec,e,request(v,old.bookingId(),75,11,13));assertThat(next.requirementId()).isNotEqualTo(old.requirementId());
        assertThat(lifecycle.readiness(ec,e).canSendForReview()).isFalse();bookings.approve(next.bookingId());
        assertThat(lifecycle.readiness(ec,e).canSendForReview()).isTrue();assertThat(snapshot(sibling.bookingId())).isEqualTo(before);
    }
    @Test @DisplayName("EC-C2 (boundary): preview honours a smaller room and explicit empty requirements")
    void previewUsesRoomRequirements() throws Exception {
        UUID e=event(),v=venue(100);
        mvc.perform(get("/api/events/"+e+"/venue-options").with(flow.as("ec1"))
            .param("expectedAttendance","75").param("requiredFacilities","").param("accessibilityNeeds",""))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.venue.venueId == '"+v+"')].verdict").value("suitable"));
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM events WHERE event_id=?",Integer.class,e)).isEqualTo(300);
    }
    @Test @DisplayName("SO-C2 EC08 (happy): every room is checked before safety approval and confirmation")
    void completeMultiVenueSafetyJourney() {
        UUID e=event();var a=requests.submit(ec,e,request(venue(350),null,300,9,17));var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
        bookings.approve(a.bookingId());bookings.approve(b.bookingId());lifecycle.sendForSafetyReview(ec,e);
        assertThatThrownBy(() -> safety.review(so,e,new SafetyApprovalRequest("approve",""))).isInstanceOf(IncompleteReadinessException.class);
        tickAll(e);safety.review(so,e,new SafetyApprovalRequest("approve",""));lifecycle.confirm(ec,e);entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,e)).isEqualTo("confirmed");
        var next=requests.submit(ec,e,request(a.venueId(),a.bookingId(),300,10,17));entityManager.flush();
        assertThat(bookingState(a.bookingId())).isEqualTo("approved");assertThat(bookingState(b.bookingId())).isEqualTo("approved");
        assertThat(bookingState(next.bookingId())).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,e)).isEqualTo("pending");
    }
}
