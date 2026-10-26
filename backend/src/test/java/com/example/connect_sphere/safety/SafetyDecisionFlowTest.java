package com.example.connect_sphere.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * SO-C5 / SO-C6 through the real API, security and PostgreSQL: the Safety
 * Officer rejects or requests changes naming the checklist items concerned,
 * and the coordinator sees them. Every test rolls back, so notifications
 * (sent after commit) are covered by {@link SafetyDecisionCommitTest}.
 */
@Tag("integration")
@Tag("SO-C5")
@Tag("SO-C6")
@Tag("EC08")
@Tag("VS17")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SafetyDecisionFlowTest {

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();
    private static final List<String> ALL_ITEMS = List.of("venue_booking", "capacity_facilities", "accessibility",
            "equipment", "registration", "safety_operations");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    /** An approved event (eo1, assigned to ec1) whose venue Venue Staff approved, still in Planning. */
    private UUID plannedEvent() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Safety Gala", 150, "none"));
        UUID venueId = venues.createVenue(new CreateVenueDto("Safety Hall " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(patch("/api/venue-bookings/" + flow.read(booking).get("bookingId").asString() + "/approve")
                .with(flow.as("vs1"))).andExpect(status().isOk());
        return eventId;
    }

    /** The same, sent by its coordinator for the safety check. */
    private UUID eventAwaitingReview() throws Exception {
        UUID eventId = plannedEvent();
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());
        return eventId;
    }

    private ResultActions decide(String as, UUID eventId, String decision, String note, List<String> items)
            throws Exception {
        String flagged = items == null ? "null"
                : "[" + String.join(",", items.stream().map(i -> "\"" + i + "\"").toList()) + "]";
        return mvc.perform(post("/api/safety/events/" + eventId + "/approval").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"note\":\"" + note + "\",\"flaggedItems\":" + flagged + "}"));
    }

    private String eventStatus(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    private int reviews(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_safety_reviews WHERE event_id = ?", Integer.class, eventId);
    }

    private int safetyDecisionEntries(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE event_id = ? "
                + "AND activity_type IN ('safety_approved', 'safety_changes_requested', 'safety_rejected')",
                Integer.class, eventId);
    }

    private List<String> timelineItems(UUID eventId, String type) {
        sync();
        return jdbc.queryForList("SELECT unnest(flagged_fields) FROM event_request_activity "
                + "WHERE event_id = ? AND activity_type = ?", String.class, eventId, type);
    }

    @Test
    @DisplayName("SO-C6 AC1, AC5, AC6, AC7, AC8, AC11, AC12 (happy): requesting changes names the items, returns the event to the coordinator, who sees them and sends it again; the Safety Officer then sees the previous decision")
    void requestingChangesReturnsTheEventWithItsItemsAndCanBeSentAgain() throws Exception {
        UUID eventId = eventAwaitingReview();

        decide("so1", eventId, "request_changes", "Add a second exit marshal", List.of("safety_operations", "accessibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("changes_requested"))
                .andExpect(jsonPath("$.flaggedItems[0]").value("accessibility"))
                .andExpect(jsonPath("$.flaggedItems[1]").value("safety_operations"))
                .andExpect(jsonPath("$.flaggedItems.length()").value(2));
        assertThat(eventStatus(eventId)).isEqualTo("safety_changes_requested");
        assertThat(timelineItems(eventId, "safety_changes_requested")).containsExactly("accessibility", "safety_operations");

        // SO-C6 AC6: back among the events still being planned.
        mvc.perform(get("/api/safety/events/planning").with(flow.as("so1")))
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].status").value("safety_changes_requested"));
        // SO-C6 AC11: the coordinator sees the reason, the items, who and when.
        mvc.perform(get("/api/events/" + eventId + "/readiness").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("changes_requested"))
                .andExpect(jsonPath("$.approvalNote").value("Add a second exit marshal"))
                .andExpect(jsonPath("$.flaggedItems.length()").value(2))
                .andExpect(jsonPath("$.reviewedBy").value("so1"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());

        // SO-C6 AC7: the coordinator sends it again.
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());
        assertThat(eventStatus(eventId)).isEqualTo("awaiting_safety_review");
        // SO-C6 AC8: the Safety Officer sees the previous decision while deciding again.
        mvc.perform(get("/api/safety/events/" + eventId + "/readiness").with(flow.as("so1")))
                .andExpect(jsonPath("$.approvalStatus").value("changes_requested"))
                .andExpect(jsonPath("$.approvalNote").value("Add a second exit marshal"))
                .andExpect(jsonPath("$.flaggedItems.length()").value(2));
    }

    @Test
    @DisplayName("SO-C5 AC1, AC6, AC7, AC10, AC11 (happy): a rejection with every item ticked returns the event to the coordinator with its reason and items")
    void aRejectionWithEveryItemTickedReturnsTheEventToTheCoordinator() throws Exception {
        UUID eventId = eventAwaitingReview();
        List<String> items = new java.util.ArrayList<>(ALL_ITEMS);
        items.add("venue_safety_" + jdbc.queryForObject(
                "SELECT booking_id FROM venue_bookings WHERE event_id = ?", UUID.class, eventId));
        for (String item : items) {
            mvc.perform(patch("/api/safety/events/" + eventId + "/readiness/items/" + item).with(flow.as("so1"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true,\"note\":\"\"}"))
                    .andExpect(status().isOk());
        }

        decide("so1", eventId, "reject", "The stage blocks both exits", List.of("capacity_facilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("rejected"))
                .andExpect(jsonPath("$.completedItems").value(7));

        assertThat(eventStatus(eventId)).isEqualTo("safety_changes_requested");
        assertThat(jdbc.queryForObject("SELECT safety_feedback FROM events WHERE event_id = ?", String.class, eventId))
                .isEqualTo("The stage blocks both exits");
        assertThat(timelineItems(eventId, "safety_rejected")).containsExactly("capacity_facilities");
        mvc.perform(get("/api/events/" + eventId + "/readiness").with(flow.as("ec1")))
                .andExpect(jsonPath("$.approvalStatus").value("rejected"))
                .andExpect(jsonPath("$.approvalNote").value("The stage blocks both exits"))
                .andExpect(jsonPath("$.flaggedItems[0]").value("capacity_facilities"));
    }

    @Test
    @DisplayName("VS17 SO-C6 (cross-cutting): safety feedback accepts this event's venue item and rejects another booking without saving")
    void venueFeedbackIsScopedToTheEventsChecklist() throws Exception {
        UUID eventId = eventAwaitingReview();
        String key = "venue_safety_" + jdbc.queryForObject(
                "SELECT booking_id FROM venue_bookings WHERE event_id = ?", UUID.class, eventId);
        decide("so1", eventId, "request_changes", "Check the venue exits", List.of("venue_safety_" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(eventStatus(eventId)).isEqualTo("awaiting_safety_review");
        assertThat(reviews(eventId)).isZero();
        assertThat(safetyDecisionEntries(eventId)).isZero();
        decide("so1", eventId, "request_changes", "Check the venue exits", List.of("equipment", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flaggedItems[0]").value("equipment"))
                .andExpect(jsonPath("$.flaggedItems[1]").value(key));
        assertThat(eventStatus(eventId)).isEqualTo("safety_changes_requested");
        assertThat(timelineItems(eventId, "safety_changes_requested")).containsExactly("equipment", key);
    }

    @Test
    @DisplayName("SO-C5 AC1, AC2, SO-C6 AC1, AC2 (negative): only a Safety Officer decides, and only while the event awaits review; otherwise nothing changes")
    void onlyASafetyOfficerDecidesAndOnlyWhileTheEventAwaitsReview() throws Exception {
        UUID planned = plannedEvent();
        decide("so1", planned, "reject", "Too early", List.of("equipment"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The Event Coordinator has not sent this event for its safety check yet."));
        assertThat(eventStatus(planned)).isEqualTo("pending");
        assertThat(reviews(planned)).isZero();

        UUID awaiting = eventAwaitingReview();
        for (String role : List.of("ec1", "eo1", "ecl1", "vs1")) {
            decide(role, awaiting, "request_changes", "Not mine to decide", List.of("equipment"))
                    .andExpect(status().isForbidden());
        }
        assertThat(eventStatus(awaiting)).isEqualTo("awaiting_safety_review");
        assertThat(reviews(awaiting)).isZero();
        assertThat(safetyDecisionEntries(awaiting)).isZero();
    }

    @Test
    @DisplayName("SO-C5 AC3, AC4, AC5, SO-C6 AC3, AC4, AC5 (negative): a blank or over-long reason, no item or an unknown item is refused, and nothing is saved")
    void invalidDecisionsAreRefusedAndNothingIsSaved() throws Exception {
        UUID eventId = eventAwaitingReview();

        decide("so1", eventId, "reject", "   ", List.of("equipment")).andExpect(status().isUnprocessableEntity());
        decide("so1", eventId, "request_changes", "x".repeat(2001), List.of("equipment"))
                .andExpect(status().isUnprocessableEntity());
        decide("so1", eventId, "reject", "Unsafe", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Select at least one checklist item the rejection concerns."));
        decide("so1", eventId, "request_changes", "Unsafe", List.of()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Select at least one checklist item that needs changing."));
        decide("so1", eventId, "reject", "Unsafe", List.of("parking")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Unknown checklist item(s): parking."));

        assertThat(eventStatus(eventId)).isEqualTo("awaiting_safety_review");
        assertThat(reviews(eventId)).isZero();
        assertThat(safetyDecisionEntries(eventId)).isZero();

        // 2,000 characters is the limit, not one over it.
        decide("so1", eventId, "request_changes", "x".repeat(2000), List.of("equipment")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SO-C5 AC8, SO-C6 AC9 (conflict): an event already decided cannot be decided again")
    void anEventAlreadyDecidedCannotBeDecidedAgain() throws Exception {
        UUID eventId = eventAwaitingReview();
        decide("so1", eventId, "request_changes", "Add a marshal", List.of("safety_operations")).andExpect(status().isOk());

        decide("so1", eventId, "reject", "Changed my mind", List.of("equipment"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("A safety decision can only be made while the event is awaiting safety review."));

        sync();
        assertThat(jdbc.queryForObject("SELECT review_status FROM event_safety_reviews WHERE event_id = ?",
                String.class, eventId)).isEqualTo("changes_requested");
        assertThat(safetyDecisionEntries(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("SO-C5 AC5, SO-C6 AC5 (negative): the database itself refuses an item that is not on the checklist")
    void theDatabaseRefusesAnItemNotOnTheChecklist() throws Exception {
        UUID eventId = eventAwaitingReview();
        decide("so1", eventId, "request_changes", "Add a marshal", List.of("safety_operations")).andExpect(status().isOk());
        sync();

        // Every real key, and none, are allowed.
        assertThat(jdbc.update("UPDATE event_safety_reviews SET flagged_items = ARRAY['venue_booking', 'capacity_facilities', "
                + "'accessibility', 'equipment', 'registration', 'safety_operations'] WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE event_safety_reviews SET flagged_items = '{}' WHERE event_id = ?", eventId)).isEqualTo(1);
        // Last: a refused statement aborts the test's transaction.
        assertThatThrownBy(() -> jdbc.update("UPDATE event_safety_reviews SET flagged_items = ARRAY['safety_operations', 'parking'] "
                + "WHERE event_id = ?", eventId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_safety_review_flagged_items");
    }
}
