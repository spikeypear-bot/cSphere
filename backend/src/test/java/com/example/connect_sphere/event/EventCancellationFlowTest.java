package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.event.service.EventLifecycleService.SafetyDecision;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * EO07 on real PostgreSQL through the real filter chain, set up through the
 * API (FlowSupport) so the steps before cancellation are proved to work with
 * it. Every test rolls back, so notifications (sent after commit) are not
 * written here; EventCancellationServiceTest checks those.
 */
@Tag("integration") @Tag("VS17") @Tag("SO-C2")
@Tag("EO07")
@Tag("ECL-C1")
@Tag("EC02")
@Tag("ECL-C7")
@Tag("EC08")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventCancellationFlowTest {
    @Autowired com.example.connect_sphere.safety.ReadinessService venueReadiness;
    private void attestVenues(UUID eventId) {
        for(var item:venueReadiness.forSafetyOfficer(eventId).items())
            venueReadiness.updateItem(flow.idOf("so1"),eventId,item.itemKey(),new com.example.connect_sphere.safety.UpdateReadinessItemRequest(true,"Verified"));
    }

    private static final String REASON_BODY = "{\"reason\":\"The client has called the event off.\"}";
    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;
    @Autowired EventLifecycleService lifecycle;
    @Autowired EquipmentLogRepository equipmentLogs;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    /** An approved event of eo1's, coordinated by ec1, with one venue Venue Staff approved. */
    private record Planned(UUID requestId, UUID eventId, UUID venueId, UUID bookingId) {
    }

    private Planned plannedEvent(String name) throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
        UUID venueId = venues.createVenue(new CreateVenueDto("EO07 Hall " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(flow.read(booking).get("bookingId").asString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/venue-bookings/" + bookingId + "/approve").with(flow.as("vs1")))
                .andExpect(status().isOk());
        sync();
        UUID requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class,
                eventId);
        return new Planned(requestId, eventId, venueId, bookingId);
    }

    /** As above, sent for review, approved by the Safety Officer and confirmed by ec1. */
    private Planned confirmedEvent(String name) throws Exception {
        Planned planned = plannedEvent(name);
        mvc.perform(post("/api/events/" + planned.eventId() + "/safety-review").with(flow.as("ec1")))
                .andExpect(status().isOk());
        attestVenues(planned.eventId());
        lifecycle.recordSafetyDecision(flow.idOf("so1"), planned.eventId(), SafetyDecision.APPROVE, null);
        mvc.perform(post("/api/events/" + planned.eventId() + "/confirm").with(flow.as("ec1")))
                .andExpect(status().isOk());
        sync();
        return planned;
    }

    private ResultActions requestCancellation(String as, UUID requestId, String body) throws Exception {
        return mvc.perform(post("/api/event-requests/" + requestId + "/cancellation-request").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Everything a cancellation could touch, to prove a refusal changed nothing. */
    private Map<String, Object> eventState(Planned planned) {
        sync();
        return jdbc.queryForMap("""
                SELECT (SELECT status::text FROM events WHERE event_id = ?) AS event_status,
                       (SELECT coordinator_id FROM events WHERE event_id = ?) AS coordinator,
                       (SELECT string_agg(status::text, ',' ORDER BY booking_id) FROM venue_bookings WHERE event_id = ?) AS bookings,
                       (SELECT count(*) FROM event_cancellation_requests WHERE event_id = ?) AS cancellations,
                       (SELECT string_agg(status, ',' ORDER BY requested_at) FROM event_cancellation_requests WHERE event_id = ?) AS cancellation_statuses,
                       (SELECT count(*) FROM event_request_activity WHERE request_id = ?) AS timeline
                """, planned.eventId(), planned.eventId(), planned.eventId(), planned.eventId(), planned.eventId(),
                planned.requestId());
    }

    private ResultActions cancelRequest(String as, UUID requestId, String body) throws Exception {
        return mvc.perform(post("/api/event-requests/" + requestId + "/cancel").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> requestState(UUID requestId) {
        sync();
        return jdbc.queryForMap("""
                SELECT status::text AS status, coordinator_id, updated_at,
                       (SELECT count(*) FROM event_request_activity WHERE request_id = r.request_id) AS timeline
                FROM event_requests r WHERE request_id = ?
                """, requestId);
    }

    // ---- Before approval (AC1, AC2, AC4, AC5) ---------------------------------

    @Test
    @DisplayName("EO07 AC2, AC11 (happy): an organiser cancels a request under review; it shows Cancelled with the reason on its timeline as a Major change")
    void anOrganiserCancelsARequestUnderReview() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Spring Gala", 80, "none"));

        cancelRequest("eo1", requestId, REASON_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.journeyStatus.label").value("Cancelled"));

        assertThat(requestState(requestId).get("status")).isEqualTo("cancelled");
        mvc.perform(get("/api/event-requests/" + requestId + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[-1].type").value("request_cancelled"))
                .andExpect(jsonPath("$[-1].message").value("The client has called the event off."))
                .andExpect(jsonPath("$[-1].actorName").value("eo1"))
                .andExpect(jsonPath("$[-1].significance").value("major"));
    }

    @Test
    @DisplayName("EO07 AC2 (happy): a cancelled request leaves the Lead's unassigned queue and can no longer be assigned")
    void aCancelledRequestLeavesTheLeadsQueueAndCannotBeAssigned() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Unassigned Fair", 80, "none"));
        mvc.perform(get("/api/event-requests/unassigned").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].requestId", hasItem(requestId.toString())));

        cancelRequest("eo1", requestId, REASON_BODY).andExpect(status().isOk());

        mvc.perform(get("/api/event-requests/unassigned").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].requestId", not(hasItem(requestId.toString()))));
        Map<String, Object> before = requestState(requestId);
        mvc.perform(post("/api/event-requests/unassigned/" + requestId + "/assign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec1") + "\"}"))
                .andExpect(status().isConflict());
        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC2, AC12 (happy): a cancelled request leaves the coordinator's review queue and can no longer be edited, approved, rejected or reassigned")
    void aCancelledRequestLeavesTheReviewQueueAndIsFinal() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Review Fair", 80, "none"));

        cancelRequest("eo1", requestId, REASON_BODY).andExpect(status().isOk());

        mvc.perform(get("/api/event-requests/queue").with(flow.as("ec1")))
                .andExpect(jsonPath("$.needsReview[*].requestId", not(hasItem(requestId.toString()))))
                .andExpect(jsonPath("$.awaitingOrganiser[*].requestId", not(hasItem(requestId.toString()))));
        Map<String, Object> before = requestState(requestId);
        mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/event-requests/" + requestId + "/reject").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/event-requests/" + requestId).with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FlowSupport.requestBody("Edited Fair", 90, "none")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isConflict());
        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (negative): an organiser of another organisation cannot cancel the request, and nothing changes")
    void anotherOrganisationsOrganiserCannotCancelARequest() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Acme Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        cancelRequest("eo3", requestId, REASON_BODY).andExpect(status().isNotFound());

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (cross-cutting): no role but the Event Organiser can cancel a request, and nothing changes")
    void otherRolesCannotCancelARequest() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Role Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        for (String other : List.of("ec1", "ecl1", "vs1", "ts1", "att1", "so1")) {
            cancelRequest(other, requestId, REASON_BODY).andExpect(status().isForbidden());
        }

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC4 (negative): a blank reason is refused with a clear message and the request is unchanged")
    void aBlankReasonIsRefused() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Blank Fair", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        cancelRequest("eo1", requestId, "{\"reason\":\"   \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Please give a reason for cancelling this request."));

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC5 (negative): a Draft or an already Cancelled request cannot be cancelled, and nothing changes")
    void aDraftOrCancelledRequestCannotBeCancelled() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody("Draft Fair", 80, "none"));
        Map<String, Object> draftBefore = requestState(draft);
        cancelRequest("eo1", draft, REASON_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("this one is Draft")));
        assertThat(requestState(draft)).isEqualTo(draftBefore);

        UUID cancelled = flow.submitted("eo1", FlowSupport.requestBody("Twice Fair", 80, "none"));
        cancelRequest("eo1", cancelled, REASON_BODY).andExpect(status().isOk());
        Map<String, Object> cancelledBefore = requestState(cancelled);
        cancelRequest("eo1", cancelled, REASON_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("this one is Cancelled")));
        assertThat(requestState(cancelled)).isEqualTo(cancelledBefore);
    }

    // ---- After approval: requesting cancellation (AC1, AC3, AC5, AC6) ------

    @Test
    @DisplayName("EO07 AC3, AC6 (happy): requesting cancellation of a Confirmed event leaves it Confirmed with its venue booked, and the organiser sees the reason and when they asked")
    void requestingCancellationLeavesTheEventAsItIs() throws Exception {
        Planned planned = confirmedEvent("Harbour Gala");

        requestCancellation("eo1", planned.requestId(), REASON_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.reason").value("The client has called the event off."));

        Map<String, Object> state = eventState(planned);
        assertThat(state.get("event_status")).isEqualTo("confirmed");
        assertThat(state.get("bookings")).isEqualTo("approved");
        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("pending"))
                .andExpect(jsonPath("$[0].reason").value("The client has called the event off."))
                .andExpect(jsonPath("$[0].requesterName").value("eo1"))
                .andExpect(jsonPath("$[0].requestedAt").isNotEmpty());
        mvc.perform(get("/api/events/" + planned.eventId()).with(flow.as("eo1")))
                .andExpect(jsonPath("$.journeyStatus.label").value("Confirmed"));
    }

    @Test
    @DisplayName("EO07 AC6 (conflict): a second cancellation request while one is pending is refused and nothing changes")
    void aSecondPendingRequestIsRefused() throws Exception {
        Planned planned = plannedEvent("Twice Gala");
        requestCancellation("eo1", planned.requestId(), REASON_BODY).andExpect(status().isOk());
        Map<String, Object> before = eventState(planned);

        requestCancellation("eo2", planned.requestId(), "{\"reason\":\"Asking again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A cancellation request for this event is already waiting for the coordinator's decision."));

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (negative): an organiser of another organisation can neither request cancellation nor see the requests, and nothing changes")
    void anotherOrganisationCannotRequestOrSeeCancellation() throws Exception {
        Planned planned = plannedEvent("Private Gala");
        Map<String, Object> before = eventState(planned);

        requestCancellation("eo3", planned.requestId(), REASON_BODY).andExpect(status().isNotFound());
        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("eo3")))
                .andExpect(status().isNotFound());

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC1 (cross-cutting): no role but the Event Organiser can request cancellation, and nothing changes")
    void otherRolesCannotRequestCancellation() throws Exception {
        Planned planned = plannedEvent("Role Gala");
        Map<String, Object> before = eventState(planned);

        for (String other : List.of("ec1", "ecl1", "vs1", "ts1", "att1", "so1")) {
            requestCancellation(other, planned.requestId(), REASON_BODY).andExpect(status().isForbidden());
        }

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC7 (negative): a coordinator who is not assigned, Venue Staff and Technical Support cannot read the event's cancellation requests")
    void outsidersCannotReadTheCancellationRequests() throws Exception {
        Planned planned = plannedEvent("Closed Gala");
        requestCancellation("eo1", planned.requestId(), REASON_BODY).andExpect(status().isOk());

        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("ec2")))
                .andExpect(status().isForbidden());
        for (String other : List.of("vs1", "ts1", "att1", "so1")) {
            mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as(other)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("ec1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("pending"));
        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("ecl1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("pending"));
    }

    @Test
    @DisplayName("EO07 AC5 (negative): a request still under review has no event to request cancellation of; it is refused and nothing changes")
    void aRequestUnderReviewCannotHaveEventCancellationRequested() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Early Gala", 80, "none"));
        Map<String, Object> before = requestState(requestId);

        requestCancellation("eo1", requestId, REASON_BODY).andExpect(status().isConflict());

        assertThat(requestState(requestId)).isEqualTo(before);
    }

    // ---- The assigned coordinator decides (AC7–AC13) ---------------------------

    private UUID pendingCancellation(Planned planned) throws Exception {
        String body = requestCancellation("eo1", planned.requestId(), REASON_BODY)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(body).get("cancellationRequestId").asString());
    }

    private ResultActions decide(String as, Planned planned, UUID cancellationId, String decision, String body)
            throws Exception {
        return mvc.perform(post("/api/events/" + planned.eventId() + "/cancellation-requests/" + cancellationId
                + "/" + decision).with(flow.as(as)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("EO07 AC8, AC11 (happy): approving cancels the event and its venue booking; the timeline shows the request and the cancellation as Major changes with the reason, and the event is kept")
    void approvingCancelsTheEventAndItsBookings() throws Exception {
        Planned planned = confirmedEvent("Approved Gala");
        UUID cancellationId = pendingCancellation(planned);

        decide("ec1", planned, cancellationId, "approve", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.decidedByName").value("ec1"));

        Map<String, Object> state = eventState(planned);
        assertThat(state.get("event_status")).isEqualTo("cancelled");
        assertThat(state.get("bookings")).isEqualTo("cancelled");
        assertThat(state.get("cancellation_statuses")).isEqualTo("approved");
        mvc.perform(get("/api/events/" + planned.eventId()).with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventName").value("Approved Gala"))
                .andExpect(jsonPath("$.journeyStatus.label").value("Cancelled"));
        mvc.perform(get("/api/events/" + planned.eventId() + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancellation_requested')].significance").value(hasItem("major")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancellation_requested')].message")
                        .value(hasItem("The client has called the event off.")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancelled')].significance").value(hasItem("major")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancelled')].actorName").value(hasItem("ec1")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancelled')].message").value(hasItem(
                        "Cancelled at the organiser's request: The client has called the event off.")))
                .andExpect(jsonPath("$[?(@.type == 'event_cancelled')].toStatus").value(hasItem("CANCELLED")));
    }

    @Test
    @DisplayName("EO07 AC8 (happy): a cancelled event leaves the coordinator's My events, the Lead's overview and the Safety Officer's list")
    void aCancelledEventLeavesEveryActiveList() throws Exception {
        Planned planned = confirmedEvent("Listed Gala");
        String id = planned.eventId().toString();
        mvc.perform(get("/api/events/assigned").with(flow.as("ec1")))
                .andExpect(jsonPath("$[*].eventId", hasItem(id)));
        mvc.perform(get("/api/events/by-coordinator").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].events[*].event.eventId", hasItem(id)));
        mvc.perform(get("/api/safety/events/confirmed").with(flow.as("so1")))
                .andExpect(jsonPath("$[*].eventId", hasItem(id)));

        decide("ec1", planned, pendingCancellation(planned), "approve", "{}").andExpect(status().isOk());
        sync();

        mvc.perform(get("/api/events/assigned").with(flow.as("ec1")))
                .andExpect(jsonPath("$[*].eventId", not(hasItem(id))));
        mvc.perform(get("/api/events/by-coordinator").with(flow.as("ecl1")))
                .andExpect(jsonPath("$[*].events[*].event.eventId", not(hasItem(id))));
        mvc.perform(get("/api/safety/events/confirmed").with(flow.as("so1")))
                .andExpect(jsonPath("$[*].eventId", not(hasItem(id))));
        mvc.perform(get("/api/safety/events/planning").with(flow.as("so1")))
                .andExpect(jsonPath("$[*].eventId", not(hasItem(id))));
    }

    @Test
    @DisplayName("EO07 AC8 (conflict): once the event is cancelled, another event can book the same venue for the same time")
    void anotherEventCanBookTheVenueAfterCancellation() throws Exception {
        Planned planned = confirmedEvent("Venue Gala");
        UUID other = flow.approvedEvent("eo3", "ec2", FlowSupport.requestBody("Other Gala", 100, "none"));
        sync();
        String sameVenue = "{\"venueId\":\"" + planned.venueId() + "\"}";
        mvc.perform(post("/api/events/" + other + "/venue-bookings").with(flow.as("ec2"))
                        .contentType(MediaType.APPLICATION_JSON).content(sameVenue))
                .andExpect(status().isUnprocessableEntity());

        decide("ec1", planned, pendingCancellation(planned), "approve", "{}").andExpect(status().isOk());
        sync();

        mvc.perform(post("/api/events/" + other + "/venue-bookings").with(flow.as("ec2"))
                        .contentType(MediaType.APPLICATION_JSON).content(sameVenue))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("EO07 AC8 (conflict): once the event is cancelled, its reserved equipment counts as available again for that period")
    void itsEquipmentIsAvailableAgainAfterCancellation() throws Exception {
        Planned planned = plannedEvent("Equipment Gala");
        UUID equipmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'EO07 mixer', 1, false, 'A')
                """, equipmentId);
        java.time.Instant start = java.time.Instant.parse("2027-03-10T01:00:00Z");
        java.time.Instant end = java.time.Instant.parse("2027-03-10T04:00:00Z");
        jdbc.update("""
                INSERT INTO equipment_logs (log_id, event_id, equipment_id, quantity, loaned_from, loaned_until)
                VALUES (?, ?, ?, 1, ?, ?)
                """, UUID.randomUUID(), planned.eventId(), equipmentId,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        assertThat(equipmentLogs.findOverlappingForEquipment(equipmentId, start, end)).hasSize(1);

        decide("ec1", planned, pendingCancellation(planned), "approve", "{}").andExpect(status().isOk());
        sync();

        assertThat(equipmentLogs.findOverlappingForEquipment(equipmentId, start, end)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class,
                planned.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("EO07 AC9 (happy): declining needs a reason, keeps the event and its booking as they were, and the organiser can ask again later")
    void decliningKeepsTheEventAndAllowsAnotherRequest() throws Exception {
        Planned planned = confirmedEvent("Declined Gala");
        UUID cancellationId = pendingCancellation(planned);
        Map<String, Object> before = eventState(planned);

        decide("ec1", planned, cancellationId, "decline", "{\"reason\":\"  \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Please give the organiser a reason for declining."));
        assertThat(eventState(planned)).isEqualTo(before);

        decide("ec1", planned, cancellationId, "decline", "{\"reason\":\"The venue deposit is non-refundable.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("declined"))
                .andExpect(jsonPath("$.decisionReason").value("The venue deposit is non-refundable."));
        Map<String, Object> after = eventState(planned);
        assertThat(after.get("event_status")).isEqualTo("confirmed");
        assertThat(after.get("bookings")).isEqualTo("approved");
        mvc.perform(get("/api/events/" + planned.eventId() + "/timeline").with(flow.as("eo1")))
                .andExpect(jsonPath("$[-1].type").value("event_cancellation_declined"))
                .andExpect(jsonPath("$[-1].message").value("The venue deposit is non-refundable."))
                .andExpect(jsonPath("$[-1].significance").value("major"));

        requestCancellation("eo1", planned.requestId(), "{\"reason\":\"Asking again\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("EO07 AC7 (negative): a coordinator who is not assigned, the Lead and the organiser cannot approve or decline, and nothing changes")
    void onlyTheAssignedCoordinatorCanDecide() throws Exception {
        Planned planned = confirmedEvent("Guarded Gala");
        UUID cancellationId = pendingCancellation(planned);
        Map<String, Object> before = eventState(planned);

        for (String decision : List.of("approve", "decline")) {
            decide("ec2", planned, cancellationId, decision, "{\"reason\":\"No\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(
                            com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException.MESSAGE));
            for (String other : List.of("ecl1", "eo1", "vs1", "ts1", "so1")) {
                decide(other, planned, cancellationId, decision, "{\"reason\":\"No\"}").andExpect(status().isForbidden());
            }
        }

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC13 (conflict): approving a request that was already declined is refused and nothing changes")
    void anAlreadyDecidedRequestCannotBeApproved() throws Exception {
        Planned planned = confirmedEvent("Late Gala");
        UUID cancellationId = pendingCancellation(planned);
        decide("ec1", planned, cancellationId, "decline", "{\"reason\":\"We will go ahead.\"}").andExpect(status().isOk());
        Map<String, Object> before = eventState(planned);

        decide("ec1", planned, cancellationId, "approve", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been declined")));

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC13 (conflict): after the event is reassigned, its pending request moves with it; only the new coordinator can decide")
    void afterReassignmentOnlyTheNewCoordinatorDecides() throws Exception {
        Planned planned = confirmedEvent("Moved Gala");
        UUID cancellationId = pendingCancellation(planned);
        mvc.perform(post("/api/event-requests/assigned/" + planned.requestId() + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());
        sync();
        Map<String, Object> before = eventState(planned);

        decide("ec1", planned, cancellationId, "approve", "{}").andExpect(status().isForbidden());
        assertThat(eventState(planned)).isEqualTo(before);

        mvc.perform(get("/api/events/" + planned.eventId() + "/cancellation-requests").with(flow.as("ec2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("pending"));
        decide("ec2", planned, cancellationId, "approve", "{}").andExpect(status().isOk());
        assertThat(eventState(planned).get("event_status")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("EO07 AC12 (negative): a cancelled event takes no further planning, booking, equipment, safety review, confirmation, reassignment or cancellation action, and nothing changes")
    void aCancelledEventIsFinal() throws Exception {
        Planned planned = plannedEvent("Final Gala");
        decide("ec1", planned, pendingCancellation(planned), "approve", "{}").andExpect(status().isOk());
        Map<String, Object> before = eventState(planned);
        String event = "/api/events/" + planned.eventId();

        mvc.perform(post(event + "/safety-review").with(flow.as("ec1"))).andExpect(status().isConflict());
        mvc.perform(post(event + "/confirm").with(flow.as("ec1"))).andExpect(status().isConflict());
        mvc.perform(post(event + "/venue-bookings").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":\"" + planned.venueId() + "\"}")).andExpect(status().isConflict());
        mvc.perform(post(event + "/venue-bookings/" + planned.bookingId() + "/cancel").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        mvc.perform(post(event + "/equipment-request").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"technicalRequirement\":\"Mics\",\"items\":[]}")).andExpect(status().isConflict());
        mvc.perform(post(event + "/reassignment-requests").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Leaving\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/event-requests/assigned/" + planned.requestId() + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isConflict());
        requestCancellation("eo1", planned.requestId(), REASON_BODY).andExpect(status().isConflict());
        cancelRequest("eo1", planned.requestId(), REASON_BODY).andExpect(status().isConflict());

        assertThat(eventState(planned)).isEqualTo(before);
    }

    @Test
    @DisplayName("EO07 AC12 (negative): Technical Support can no longer review or reserve for a cancelled event's equipment request, and it leaves their queue")
    void aCancelledEventsEquipmentRequestTakesNoFurtherAction() throws Exception {
        Planned planned = plannedEvent("Equipment Final Gala");
        UUID equipmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'EO07 speaker', 4, false, 'A')
                """, equipmentId);
        String created = mvc.perform(post("/api/events/" + planned.eventId() + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage sound\",\"items\":[{\"equipmentId\":\"%s\",\"quantity\":1}]}"
                                .formatted(equipmentId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID equipmentRequestId = UUID.fromString(flow.read(created).get("requestId").asString());
        sync();
        mvc.perform(get("/api/equipment-requests/processing").with(flow.as("ts1")))
                .andExpect(jsonPath("$[*].requestId", hasItem(equipmentRequestId.toString())));

        decide("ec1", planned, pendingCancellation(planned), "approve", "{}").andExpect(status().isOk());
        sync();

        mvc.perform(get("/api/equipment-requests/processing").with(flow.as("ts1")))
                .andExpect(jsonPath("$[*].requestId", not(hasItem(equipmentRequestId.toString()))));
        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"equipmentId\":\"%s\",\"quantity\":1,\"serialNumber\":null,\"start\":\"2027-03-10T01:00:00Z\",\"end\":\"2027-03-10T04:00:00Z\"}"
                                .formatted(planned.eventId(), equipmentId)))
                .andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/equipment-requests/" + equipmentRequestId + "/status").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"rejectReason\":\"n/a\"}"))
                .andExpect(status().isConflict());
        sync();
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment_requests WHERE request_id = ?",
                String.class, equipmentRequestId)).isEqualTo("processing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class,
                planned.eventId())).isZero();
    }
}
