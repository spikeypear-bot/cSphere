package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.connect_sphere.safety.dto.SafetyApprovalRequest;
import com.example.connect_sphere.safety.dto.UpdateReadinessItemRequest;
import com.example.connect_sphere.safety.service.IncompleteReadinessException;
import com.example.connect_sphere.safety.service.ReadinessService;
import com.example.connect_sphere.safety.service.VenueSafetyChecklist;
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

@SpringBootTest @AutoConfigureMockMvc @Transactional
@Tag("integration") @Tag("EC03") @Tag("VS03") @Tag("VS13") @Tag("VS17") @Tag("EC-C2") @Tag("EC08") @Tag("EC10") @Tag("SO-C2") @Tag("VS04") @Tag("VS25")
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
    @Autowired BookingPlanService plan;
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
    int bookingCount(UUID event) { entityManager.flush(); return jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id=?",Integer.class,event); }
    void moveTo(UUID event,String status) { entityManager.flush(); jdbc.update("UPDATE events SET status=?::event_status WHERE event_id=?",status,event); entityManager.clear(); }
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

    @Test @DisplayName("EC10 (conflict): a booking that already has a pending replacement cannot get a second one, and nothing is saved")
    void secondPendingReplacementIsRefused() {
        UUID e=event(),v=venue(100);var old=requests.submit(ec,e,request(v,null,75,10,12));bookings.approve(old.bookingId());
        requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));int before=bookingCount(e);
        assertThatThrownBy(() -> requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12)))
            .isInstanceOf(VenueBookingStateException.class).hasMessage("This requirement already has an active booking or replacement. Refresh this event.");
        assertThat(bookingCount(e)).isEqualTo(before);
    }
    @Test @DisplayName("EC10 (negative): a booking whose replacement was approved cannot be replaced again, and nothing is saved")
    void approvedReplacementIsNotReplacedAgain() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(old.bookingId(),"Unavailable",null,null);
        bookings.approve(requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12)).bookingId());int before=bookingCount(e);
        assertThatThrownBy(() -> requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12))).isInstanceOf(VenueBookingStateException.class);
        assertThat(bookingCount(e)).isEqualTo(before);
    }
    @Test @DisplayName("EC10 (negative): a booking still waiting for Venue Staff cannot be replaced, and nothing is saved")
    void pendingBookingIsNotReplaceable() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));int before=bookingCount(e);
        assertThatThrownBy(() -> requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12)))
            .isInstanceOf(VenueBookingStateException.class).hasMessage("This booking is not eligible for a replacement. Refresh this event.");
        assertThat(bookingCount(e)).isEqualTo(before);
    }
    @Test @DisplayName("EC10 (negative): a completed event takes no replacement venue, and nothing is saved")
    void completedEventTakesNoReplacement() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());
        moveTo(e,"completed");int before=bookingCount(e);
        assertThatThrownBy(() -> requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12)))
            .isInstanceOf(VenueBookingStateException.class).hasMessageContaining("only be made while an event is in Planning");
        assertThat(bookingCount(e)).isEqualTo(before);
    }
    @Test @DisplayName("EC03 EC-C2 (boundary): a venue whose end time is not after its start time is refused, and nothing is saved")
    void endMustBeAfterStart() {
        UUID e=event(),v=venue(100);
        for (int end : new int[] {10, 9})
            assertThatThrownBy(() -> requests.submit(ec,e,request(v,null,75,10,end))).isInstanceOf(InvalidVenueBookingException.class)
                .hasMessage("Each venue must have an end date/time later than its start date/time.");
        assertThat(bookingCount(e)).isZero();
    }
    @Test @DisplayName("VS25 (conflict): an approved booking cannot be cancelled while its replacement is pending, and stays approved")
    void cancelWaitsForPendingReplacement() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());
        requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));
        assertThatThrownBy(() -> requests.cancel(ec,e,old.bookingId(),"Not needed")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("Resolve the pending replacement request before cancelling the original booking.");
        assertThat(bookingState(old.bookingId())).isEqualTo("approved");
    }
    @Test @DisplayName("VS25 (negative): bookings of a cancelled or completed event cannot be cancelled, and stay as they were")
    void cancelledEventBookingsAreFrozen() {
        for (String closed : List.of("cancelled","completed")) {
            UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));moveTo(e,closed);
            assertThatThrownBy(() -> requests.cancel(ec,e,b.bookingId(),null)).isInstanceOf(VenueBookingStateException.class)
                .hasMessage("Bookings for completed or cancelled events cannot be changed.");
            assertThat(bookingState(b.bookingId())).isEqualTo("pending");
        }
    }
    @Test @DisplayName("VS25 EC10 (happy): cancelling a pending replacement keeps the original booking and its requirement")
    void cancellingAReplacementKeepsTheRequirement() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());
        var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));
        requests.cancel(ec,e,next.bookingId(),"Keep the original room");
        entityManager.flush();
        assertThat(bookingState(next.bookingId())).isEqualTo("cancelled");assertThat(bookingState(old.bookingId())).isEqualTo("approved");
        assertThat(plan.required(old.requirementId())).isTrue();
    }
    @Test @DisplayName("EC08 (negative): an active booking must be cancelled before its requirement is withdrawn")
    void withdrawNeedsAnInactiveBooking() {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,b.bookingId(),"No longer needed")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("Cancel an active booking before withdrawing its requirement.");
        assertThat(plan.required(b.requirementId())).isTrue();
    }
    @Test @DisplayName("EC08 (negative): withdrawing a requirement needs a reason, and a blank one withdraws nothing")
    void withdrawNeedsAReason() {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(b.bookingId(),"Unavailable",null,null);
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,b.bookingId(),"   ")).isInstanceOf(InvalidVenueBookingException.class)
            .hasMessage("Explain why this venue is no longer required.");
        assertThat(plan.required(b.requirementId())).isTrue();
    }
    @Test @DisplayName("EC08 EC10 (conflict): a requirement with a pending replacement cannot be withdrawn")
    void withdrawWaitsForReplacement() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(old.bookingId(),"Unavailable",null,null);
        requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,old.bookingId(),"No longer needed")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("This requirement still has an active booking or replacement.");
        assertThat(plan.required(old.requirementId())).isTrue();
    }
    @Test @DisplayName("EC08 VS25 (boundary): a requirement can be withdrawn once; withdrawing it again, or after cancelling its booking already did, is refused")
    void withdrawOnlyOnce() {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(b.bookingId(),"Unavailable",null,null);
        requests.withdrawRequirement(ec,e,b.bookingId(),"Breakout removed");
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,b.bookingId(),"Again")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("This requirement is already withdrawn or historical.");
        var cancelled=requests.submit(ec,e,request(venue(100),null,75,10,12));requests.cancel(ec,e,cancelled.bookingId(),"Room dropped");
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,cancelled.bookingId(),"Again")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("This requirement is already withdrawn or historical.");
    }
    @Test @DisplayName("EC08 (negative): requirements can only be withdrawn while the event is in Planning")
    void withdrawOnlyInPlanning() {
        UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.reject(b.bookingId(),"Unavailable",null,null);
        moveTo(e,"awaiting_safety_review");
        assertThatThrownBy(() -> requests.withdrawRequirement(ec,e,b.bookingId(),"Removed")).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("Withdraw requirements while the event is in Planning.");
        assertThat(plan.required(b.requirementId())).isTrue();
    }
    @Test @DisplayName("EC-C2 (negative): an empty batch, or one with an empty entry or a booking missing its venue, is refused and nothing is saved")
    void batchNeedsAVenueForEveryBooking() {
        UUID e=event();
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of())))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessage("Add at least one venue before submitting.");
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(venue(100),null,75,10,12),request(null,null,75,13,15)))))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessage("Select a venue for every booking.");
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(Arrays.asList((SubmitVenueBookingRequest) null))))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessage("Select a venue for every booking.");
        assertThat(bookingCount(e)).isZero();
    }
    @Test @DisplayName("EC-C2 (negative): the same venue twice in one batch is refused, whether or not the times overlap")
    void batchTakesEachVenueOnce() {
        UUID e=event(),v=venue(100);
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(v,null,75,9,11),request(v,null,75,13,15)))))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessage("This venue has already been added to this event. Choose another venue.");
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(v,null,75,13,15),request(v,null,75,9,11)))))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessage("This venue has already been added to this event. Choose another venue.");
        assertThatThrownBy(() -> requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(request(v,null,75,9,12),request(v,null,75,11,15)))))
            .isInstanceOf(InvalidVenueBookingException.class).hasMessageContaining("overlapping occupied times");
        assertThat(bookingCount(e)).isZero();
    }
    @Test @DisplayName("EC-C2 (happy): the assigned coordinator reads one booking's own history")
    void coordinatorReadsBookingHistory() throws Exception {
        UUID e=event();var a=requests.submit(ec,e,request(venue(350),null,300,9,17));bookings.approve(a.bookingId());
        assertThat(requests.history(ec,e,a.bookingId())).hasSize(2);
        assertThatThrownBy(() -> requests.history(ec,e,UUID.randomUUID())).isInstanceOf(VenueBookingNotFoundException.class);
    }
    @Test @DisplayName("EC03 EC-C2 (boundary): the preview marks a room missing a needed accessibility feature as needing a justification, and one missing a facility as blocked, saying why")
    void previewExplainsMissingProvisions() throws Exception {
        UUID e=event(),v=venue(100);
        mvc.perform(get("/api/events/"+e+"/venue-options").with(flow.as("ec1"))
            .param("expectedAttendance","75").param("requiredFacilities","").param("accessibilityNeeds","step_free_access"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.venue.venueId == '"+v+"')].verdict").value("needs_justification"))
            .andExpect(jsonPath("$[?(@.venue.venueId == '"+v+"')].reasons[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Missing accessibility:"))));
        mvc.perform(get("/api/events/"+e+"/venue-options").with(flow.as("ec1"))
            .param("expectedAttendance","75").param("requiredFacilities","stage").param("accessibilityNeeds",""))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.venue.venueId == '"+v+"')].verdict").value("blocked"))
            .andExpect(jsonPath("$[?(@.venue.venueId == '"+v+"')].reasons[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Missing facilities:"))));
    }
    @Test @DisplayName("EC-C2 (happy): venues in a batch sent without their own times are booked for the event's times")
    void batchFallsBackToTheEventTimes() {
        UUID e=event();
        var result=requests.submitBatch(ec,e,new SubmitVenueBookingRequest.Batch(List.of(
            new SubmitVenueBookingRequest(venue(350),null,null,null,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,new BookingRequirements(300,List.of(),List.of(),"Plenary")),
            new SubmitVenueBookingRequest(venue(100),null,null,null,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,new BookingRequirements(75,List.of(),List.of(),"Breakout")))));
        assertThat(result).extracting(EventVenueBookingDto::startDatetime).allMatch(at(8)::isEqual);
        assertThat(result).extracting(EventVenueBookingDto::endDatetime).allMatch(at(18)::isEqual);
    }
    @Test @DisplayName("EC10 (happy): a replacement venue can be requested while the event awaits or has passed its safety review, which returns it to Planning")
    void replacementDuringSafetyReview() {
        for (String status : List.of("awaiting_safety_review","safety_approved")) {
            UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());moveTo(e,status);
            var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));entityManager.flush();
            assertThat(bookingState(next.bookingId())).isEqualTo("pending");assertThat(bookingState(old.bookingId())).isEqualTo("approved");
            assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,e)).isEqualTo("pending");
        }
    }
    @Test @DisplayName("VS25 EC10 (happy): cancelling an approved, or flagged, replacement withdraws the requirement it fulfilled, and the superseded original cannot be replaced again")
    void cancellingAnApprovedReplacementWithdrawsItsRequirement() {
        for (String status : List.of("approved","attention")) {
            UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());
            var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,11,13));bookings.approve(next.bookingId());
            entityManager.flush();jdbc.update("UPDATE venue_bookings SET status=?::venue_booking_status WHERE booking_id=?",status,next.bookingId());entityManager.clear();
            requests.cancel(ec,e,next.bookingId(),"Session removed");entityManager.flush();
            assertThat(bookingState(next.bookingId())).isEqualTo("cancelled");assertThat(plan.required(next.requirementId())).isFalse();
            // The original was superseded ("changed") when the replacement was approved; it cannot be replaced again.
            assertThatThrownBy(() -> requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12))).isInstanceOf(VenueBookingStateException.class)
                .hasMessage("This booking is not eligible for a replacement. Refresh this event.");
        }
    }
    @Test @DisplayName("EC10 VS13 VS03 (happy): a pending booking that an operational issue now overlaps can be replaced, and approving the replacement supersedes it")
    void affectedPendingBookingCanBeReplaced() {
        UUID e=event(),v=venue(100);var old=requests.submit(ec,e,request(v,null,75,10,12));entityManager.flush();
        jdbc.update("INSERT INTO venue_operational_issues(issue_id,venue_id,description,affected_from,affected_until,created_by) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(),v,"Ceiling repair",at(10),at(11),vs);
        var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));entityManager.flush();
        assertThat(bookingState(next.bookingId())).isEqualTo("pending");assertThat(next.replacesBookingId()).isEqualTo(old.bookingId());
        bookings.approve(next.bookingId());entityManager.flush();
        assertThat(bookingState(next.bookingId())).isEqualTo("approved");assertThat(bookingState(old.bookingId())).isEqualTo("changed");
    }
    @Test @DisplayName("EC-C2 (boundary): a completed or cancelled event still lists its bookings, with their timing no longer editable")
    void closedEventBookingsAreReadOnly() {
        for (String closed : List.of("completed","cancelled")) {
            UUID e=event();var b=requests.submit(ec,e,request(venue(100),null,75,10,12));
            assertThat(requests.bookingsForEvent(ec,e).get(0).timing().editable()).isTrue();
            moveTo(e,closed);
            var listed=requests.bookingsForEvent(ec,e);
            assertThat(listed).extracting(EventVenueBookingDto::bookingId).containsExactly(b.bookingId());
            assertThat(listed.get(0).timing().editable()).isFalse();
        }
    }
    @Test @DisplayName("VS13 EC03 (boundary): only an operational issue that overlaps the event blocks a venue; one without dates, before or after does not")
    void onlyOverlappingIssuesBlockAVenue() throws Exception {
        UUID e=event(),quiet=venue(100),blocked=venue(100);
        String insert="INSERT INTO venue_operational_issues(issue_id,venue_id,description,affected_from,affected_until,created_by) VALUES (?,?,?,?,?,?)";
        jdbc.update(insert,UUID.randomUUID(),quiet,"Lift serviced some day",null,null,vs);
        jdbc.update(insert,UUID.randomUUID(),quiet,"Morning cleaning",at(5),at(7),vs);
        jdbc.update(insert,UUID.randomUUID(),quiet,"Evening rehearsal",at(19),at(20),vs);
        jdbc.update(insert,UUID.randomUUID(),blocked,"Ceiling repair",at(9),at(10),vs);
        mvc.perform(get("/api/events/"+e+"/venue-options").with(flow.as("ec1"))
            .param("expectedAttendance","75").param("requiredFacilities","").param("accessibilityNeeds",""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.venue.venueId == '"+quiet+"')].verdict").value("suitable"))
            .andExpect(jsonPath("$[?(@.venue.venueId == '"+blocked+"')].verdict").value("blocked"))
            .andExpect(jsonPath("$[?(@.venue.venueId == '"+blocked+"')].reasons[*]").value(org.hamcrest.Matchers.hasItem("Venue unavailable: Ceiling repair.")));
    }
    @Test @DisplayName("VS03 EC10 (conflict): once the issue that blocked a pending booking is resolved, its replacement can no longer be approved, and both stay pending")
    void replacementOfARecoveredBookingIsNotApproved() {
        UUID e=event(),v=venue(100);var old=requests.submit(ec,e,request(v,null,75,10,12));entityManager.flush();
        UUID issue=UUID.randomUUID();
        jdbc.update("INSERT INTO venue_operational_issues(issue_id,venue_id,description,affected_from,affected_until,created_by) VALUES (?,?,?,?,?,?)",
            issue,v,"Ceiling repair",at(10),at(11),vs);
        var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));entityManager.flush();
        jdbc.update("DELETE FROM venue_operational_issues WHERE issue_id=?",issue);
        assertThatThrownBy(() -> bookings.approve(next.bookingId())).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("The original booking no longer requires replacement. Refresh this event.");
        assertThat(bookingState(next.bookingId())).isEqualTo("pending");assertThat(bookingState(old.bookingId())).isEqualTo("pending");
    }
    @Test @DisplayName("VS03 (negative): a booking is not approved once the event has grown past the venue's capacity, and stays pending")
    void approvalRechecksCapacity() {
        UUID e=event(),v=venue(350);
        var b=requests.submit(ec,e,new SubmitVenueBookingRequest(v,null,null,null,at(10),at(12),BigDecimal.ZERO,BigDecimal.ZERO,null,null));
        entityManager.flush();jdbc.update("UPDATE events SET expected_attendance=400 WHERE event_id=?",e);entityManager.clear();
        assertThatThrownBy(() -> bookings.approve(b.bookingId())).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("The venue no longer meets the approved event requirements. Review current suitability.");
        assertThat(bookingState(b.bookingId())).isEqualTo("pending");
    }
    @Test @DisplayName("VS03 EC10 (happy): approving the replacement of a flagged booking supersedes it")
    void approvingAFlaggedBookingsReplacementSupersedesIt() {
        UUID e=event();var old=requests.submit(ec,e,request(venue(100),null,75,10,12));bookings.approve(old.bookingId());entityManager.flush();
        jdbc.update("UPDATE venue_bookings SET status='attention' WHERE booking_id=?",old.bookingId());entityManager.clear();
        var next=requests.submit(ec,e,request(venue(100),old.bookingId(),75,10,12));bookings.approve(next.bookingId());entityManager.flush();
        assertThat(bookingState(old.bookingId())).isEqualTo("changed");assertThat(bookingState(next.bookingId())).isEqualTo("approved");
    }
    @Test @DisplayName("VS03 (negative): a booking is not approved once the event needs a facility the venue lacks, and stays pending")
    void approvalRechecksFacilities() {
        UUID e=event(),v=venue(350);
        var b=requests.submit(ec,e,new SubmitVenueBookingRequest(v,null,null,null,at(10),at(12),BigDecimal.ZERO,BigDecimal.ZERO,null,null));
        entityManager.flush();jdbc.update("UPDATE events SET required_facilities='{stage}' WHERE event_id=?",e);entityManager.clear();
        assertThatThrownBy(() -> bookings.approve(b.bookingId())).isInstanceOf(VenueBookingStateException.class)
            .hasMessage("The venue no longer meets the approved event requirements. Review current suitability.");
        assertThat(bookingState(b.bookingId())).isEqualTo("pending");
    }
}
