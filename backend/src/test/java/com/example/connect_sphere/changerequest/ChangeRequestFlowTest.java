package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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

import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * EO03 + EC-NEW3 end to end through the real API, security, services and
 * PostgreSQL: the organiser asks, the assigned coordinator sees the impact on
 * the arrangements already made and decides. Every test rolls back.
 *
 * Fixture times: FlowSupport's events run 10 Mar 2027, 09:00-12:00 Singapore
 * time. Clash boundaries are checked with exact minutes.
 */
@Tag("integration")
@Tag("EO03")
@Tag("EC-NEW3")
@Tag("EC-NEW5")
@Tag("ECL-C7")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChangeRequestFlowTest {

    private static final String DAY = "2027-03-10T";
    private static final List<VenueOperatingHourDto> ALL_WEEK = hours(LocalTime.of(0, 0), LocalTime.of(23, 59));

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

    private static List<VenueOperatingHourDto> hours(LocalTime open, LocalTime close) {
        return IntStream.rangeClosed(1, 7).mapToObj(day -> new VenueOperatingHourDto(day, open, close)).toList();
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    /** The full set of details, as the organiser's form sends them. */
    private static String details(String name, String start, String end, int attendance) {
        return """
                {"eventName":"%s","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"%s%s:00+08:00","endDatetime":"%s%s:00+08:00","expectedAttendance":%d,
                 "venueRequirements":"Theatre seating","accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(name, DAY, start, DAY, end, attendance);
    }

    private ResultActions ask(String organiser, UUID requestId, String reason, String details) throws Exception {
        return mvc.perform(post("/api/change-requests").with(flow.as(organiser)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                        + ",\"details\":" + details + "}"));
    }

    private UUID asked(UUID requestId, String details) throws Exception {
        String body = ask("eo1", requestId, "Plans changed", details).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(body).get("changeRequestId").asString());
    }

    private JsonNode view(UUID changeRequestId, String as) throws Exception {
        return flow.read(mvc.perform(get("/api/change-requests/" + changeRequestId).with(flow.as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> attention(UUID changeRequestId) throws Exception {
        List<String> keys = new ArrayList<>();
        view(changeRequestId, "ec1").get("impact").forEach(i -> {
            if ("attention".equals(i.get("status").asString())) {
                keys.add(i.get("key").asString());
            }
        });
        return keys;
    }

    private ResultActions approve(String coordinator, UUID changeRequestId, List<String> acknowledged) throws Exception {
        String keys = String.join(",", acknowledged.stream().map(k -> "\"" + k + "\"").toList());
        return mvc.perform(post("/api/change-requests/" + changeRequestId + "/approve").with(flow.as(coordinator))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[" + keys + "]}"));
    }

    private void withdraw(UUID changeRequestId) throws Exception {
        mvc.perform(post("/api/change-requests/" + changeRequestId + "/withdraw").with(flow.as("eo1")))
                .andExpect(status().isOk());
        sync();
    }

    private UUID requestOf(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);
    }

    private UUID venue(String name, int capacity, List<VenueOperatingHourDto> openingHours, int setup, int turnaround) {
        UUID id = venues.createVenue(new CreateVenueDto(name + " " + UUID.randomUUID(), capacity,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), openingHours)).venueId();
        sync();
        jdbc.update("UPDATE venues SET setup_minutes = ?, turnaround_minutes = ? WHERE venue_id = ?", setup, turnaround, id);
        return id;
    }

    private UUID bookedAndApproved(UUID eventId, UUID venueId, String coordinator) throws Exception {
        String created = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as(coordinator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(flow.read(created).get("bookingId").asString());
        mvc.perform(patch("/api/venue-bookings/" + bookingId + "/approve").with(flow.as("vs1"))).andExpect(status().isOk());
        sync();
        return bookingId;
    }

    private String eventField(UUID eventId, String column) {
        sync();
        return jdbc.queryForObject("SELECT " + column + "::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    // ---- EO03: asking --------------------------------------------------------

    @Test
    @DisplayName("EO03 AC2, AC25, AC28, AC29, AC36, AC37 (happy): a submitted request's change is saved as Pending, changes nothing yet, and is listed for its request")
    void askingChangesNothingUntilDecided() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Ask Gala", 150, "none"));
        UUID id = asked(requestId, details("Ask Gala", "09:00", "12:00", 200));

        JsonNode mine = view(id, "eo1");
        assertThat(mine.get("status").asString()).isEqualTo("pending");
        assertThat(mine.get("reason").asString()).isEqualTo("Plans changed");
        assertThat(mine.get("requestedByName").asString()).isEqualTo("eo1");
        assertThat(mine.get("changes")).hasSize(1);
        assertThat(mine.get("changes").get(0).get("field").asString()).isEqualTo("expectedAttendance");
        assertThat(mine.get("changes").get(0).get("before").asInt()).isEqualTo(150);
        assertThat(mine.get("changes").get(0).get("after").asInt()).isEqualTo(200);
        assertThat(mine.get("impact").isNull()).isTrue();
        assertThat(mine.get("canWithdraw").asBoolean()).isTrue();
        // Nothing is applied yet: everyone still sees 150.
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM event_requests WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(150);
        mvc.perform(get("/api/change-requests").param("requestId", requestId.toString()).with(flow.as("eo1")))
                .andExpect(jsonPath("$[0].changeRequestId").value(id.toString()));
    }

    @Test
    @DisplayName("EO03 AC11, AC12, AC13, AC14, AC18, AC20, AC21 (negative): a blank or over-long reason, invalid details, a decimal attendance or no real change is refused, and nothing is saved")
    void invalidRequestsAreRefused() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Rules Gala", 150, "none"));
        String valid = details("Rules Gala", "09:00", "12:00", 180);

        ask("eo1", requestId, "   ", valid).andExpect(status().isUnprocessableEntity());
        ask("eo1", requestId, "x".repeat(2001), valid).andExpect(status().isUnprocessableEntity());
        ask("eo1", requestId, "Ends when it starts", details("Rules Gala", "09:00", "09:00", 150))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.endDatetime").value("The end date & time must be after the start date & time."));
        ask("eo1", requestId, "Decimal", details("Rules Gala", "09:00", "12:00", 150).replace("150", "150.5"))
                .andExpect(status().isBadRequest());
        ask("eo1", requestId, "Nothing", FlowSupport.requestBody("Rules Gala", 150, "none"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Nothing was changed. Change at least one detail, or cancel."));
        // The same moment in UTC, with extra spaces: still no change.
        ask("eo1", requestId, "Same", FlowSupport.requestBody("  Rules Gala ", 150, "none")
                        .replace("2027-03-10T09:00:00+08:00", "2027-03-10T01:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
        sync();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_change_requests WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        // 2,000 characters after trimming is accepted.
        ask("eo1", requestId, " " + "y".repeat(2000) + " ", valid).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("EO03 AC3, AC4, AC5 (negative): a draft, a request awaiting clarification, a rejected request or an event that has started cannot be changed, each with the reason")
    void onlyChangeableRecordsAccept() throws Exception {
        UUID draft = flow.draft("eo1", FlowSupport.requestBody("Draft Gala", 150, "none"));
        ask("eo1", draft, "x", details("Draft Gala", "09:00", "12:00", 160)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A draft is edited directly. Open it and change the details there."));

        UUID asked = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Question Gala", 150, "none"));
        mvc.perform(post("/api/event-requests/" + asked + "/clarifications").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Final numbers?\",\"flaggedFields\":[]}"))
                .andExpect(status().isOk());
        ask("eo1", asked, "x", details("Question Gala", "09:00", "12:00", 160)).andExpect(status().isConflict());

        UUID rejected = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Rejected Gala", 150, "none"));
        mvc.perform(post("/api/event-requests/" + rejected + "/reject").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}")).andExpect(status().isOk());
        ask("eo1", rejected, "x", details("Rejected Gala", "09:00", "12:00", 160)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This request was rejected, so it can no longer be changed."));

        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Started Gala", 150, "none"));
        UUID started = requestOf(eventId);
        jdbc.update("UPDATE events SET start_datetime = now() - interval '1 minute' WHERE event_id = ?", eventId);
        sync();
        ask("eo1", started, "x", details("Started Gala", "09:00", "12:00", 160)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The event has already started, so it can no longer be changed."));
    }

    @Test
    @DisplayName("EO03 AC1 (cross-cutting): another organisation cannot ask, see or withdraw; other roles cannot ask")
    void onlyTheOwningOrganisation() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Private Gala", 150, "none"));
        UUID id = asked(requestId, details("Private Gala", "09:00", "12:00", 160));

        ask("eo3", requestId, "Mine now", details("Private Gala", "09:00", "12:00", 170)).andExpect(status().isNotFound());
        mvc.perform(get("/api/change-requests/" + id).with(flow.as("eo3"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/change-requests/" + id + "/withdraw").with(flow.as("eo3"))).andExpect(status().isNotFound());
        ask("ec1", requestId, "x", details("Private Gala", "09:00", "12:00", 170)).andExpect(status().isForbidden());
        ask("vs1", requestId, "x", details("Private Gala", "09:00", "12:00", 170)).andExpect(status().isForbidden());
        // A colleague in the same organisation may (AU04).
        assertThat(view(id, "eo2").get("canWithdraw").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("EO03 AC26, AC27 (conflict): only one pending change per request, enforced by the database even for simultaneous submissions")
    void onePendingAtATime() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Once Gala", 150, "none"));
        UUID id = asked(requestId, details("Once Gala", "09:00", "12:00", 160));

        ask("eo1", requestId, "Another", details("Once Gala", "09:00", "12:00", 170)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This event already has a change request waiting for a decision. Withdraw it or wait for the decision."));
        // What the service checks is also what the database guarantees.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO event_change_requests (change_request_id, request_id, requested_by, reason, original_values,
                    proposed_values, status, requested_at)
                SELECT gen_random_uuid(), request_id, requested_by, 'dup', original_values, proposed_values, 'pending', now()
                FROM event_change_requests WHERE change_request_id = ?""", id))
                .hasMessageContaining("idx_one_pending_change_request");
    }

    @Test
    @DisplayName("EO03 AC32, AC33 (conflict): with no coordinator yet it waits; the coordinator assigned later decides it")
    void anUnassignedRequestWaitsForItsCoordinator() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody("Waiting Gala", 150, "none"));
        UUID id = asked(requestId, details("Waiting Gala", "09:00", "12:00", 160));
        approve("ec1", id, List.of()).andExpect(status().isForbidden());

        mvc.perform(post("/api/event-requests/unassigned/" + requestId + "/assign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec1") + "\"}"))
                .andExpect(status().isOk());
        sync();
        approve("ec1", id, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    @DisplayName("EO03 AC34, AC35, EC-NEW3 AC30, AC41 (conflict): the organiser withdraws a pending change, and it cannot be withdrawn or decided again")
    void withdrawing() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Withdrawn Gala", 150, "none"));
        UUID id = asked(requestId, details("Withdrawn Gala", "09:00", "12:00", 160));
        withdraw(id);

        assertThat(view(id, "eo1").get("status").asString()).isEqualTo("withdrawn");
        mvc.perform(post("/api/change-requests/" + id + "/withdraw").with(flow.as("eo1"))).andExpect(status().isConflict());
        approve("ec1", id, List.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This change request has already been withdrawn."));
    }

    // ---- EC-NEW3: access ---------------------------------------------------

    @Test
    @DisplayName("EC-NEW3 AC1, AC2, AC3 (cross-cutting): only the assigned coordinator decides; another coordinator is refused; the Lead sees it and its impact but cannot decide")
    void whoMayDecide() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Decide Gala", 150, "none"));
        UUID id = asked(requestId, details("Decide Gala", "09:00", "12:00", 160));

        mvc.perform(get("/api/change-requests/" + id).with(flow.as("ec2"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(NotAssignedCoordinatorException.MESSAGE));
        approve("ec2", id, List.of()).andExpect(status().isForbidden());
        JsonNode lead = view(id, "ecl1");
        assertThat(lead.get("canDecide").asBoolean()).isFalse();
        assertThat(lead.get("impact")).isNotEmpty();
        approve("ecl1", id, List.of()).andExpect(status().isForbidden());
        approve("eo1", id, List.of()).andExpect(status().isForbidden());
        // ec1's queue holds it; ec2's does not.
        mvc.perform(get("/api/change-requests").with(flow.as("ec1")))
                .andExpect(jsonPath("$[?(@.changeRequestId == '" + id + "')]").isNotEmpty());
        mvc.perform(get("/api/change-requests").with(flow.as("ec2")))
                .andExpect(jsonPath("$[?(@.changeRequestId == '" + id + "')]").isEmpty());
        assertThat(view(id, "ec1").get("canDecide").asBoolean()).isTrue();
    }

    // ---- EC-NEW3: impact boundaries --------------------------------------------

    @Test
    @DisplayName("EC-NEW3 AC8, AC10, AC11, AC12, AC13 (boundary): occupied times that only meet do not clash; one minute into the next booking's setup does; the event's own booking never does")
    void clashBoundaries() throws Exception {
        UUID venueId = venue("Boundary Hall", 300, ALL_WEEK, 0, 0);
        UUID mine = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Mine Gala", 150, "none"));
        bookedAndApproved(mine, venueId, "ec1");
        // Another event at 13:00-15:00 whose booking needs 30 minutes' setup: occupied from 12:30.
        jdbc.update("UPDATE venues SET setup_minutes = 30 WHERE venue_id = ?", venueId);
        UUID other = flow.approvedEvent("eo3", "ec2", details("Other Fair", "13:00", "15:00", 50));
        bookedAndApproved(other, venueId, "ec2");
        UUID requestId = requestOf(mine);
        String clash = "venue:";

        UUID meets = asked(requestId, details("Mine Gala", "09:00", "12:30", 150));
        assertThat(attention(meets)).noneMatch(k -> k.startsWith(clash) && k.endsWith(":clash"));
        withdraw(meets);

        UUID overlaps = asked(requestId, details("Mine Gala", "09:00", "12:31", 150));
        JsonNode impact = view(overlaps, "ec1").get("impact");
        assertThat(attention(overlaps)).anyMatch(k -> k.endsWith(":clash"));
        // Another event's name is never shown (ECL-C7).
        assertThat(impact.toString()).contains("Booked by another event").doesNotContain("Other Fair");
        withdraw(overlaps);

        UUID within = asked(requestId, details("Mine Gala", "09:30", "11:30", 150));
        assertThat(attention(within)).noneMatch(k -> k.endsWith(":clash"));
    }

    @Test
    @DisplayName("EC-NEW3 AC14, AC15, AC17 (boundary): ending exactly at closing time fits, one minute later does not; overnight is outside hours; capacity equal fits, one more does not")
    void hoursAndCapacityBoundaries() throws Exception {
        UUID venueId = venue("Hours Hall", 200, hours(LocalTime.of(8, 0), LocalTime.of(18, 0)), 0, 0);
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Hours Gala", 150, "none"));
        bookedAndApproved(eventId, venueId, "ec1");
        UUID requestId = requestOf(eventId);

        UUID atClose = asked(requestId, details("Hours Gala", "09:00", "18:00", 200));
        assertThat(attention(atClose)).noneMatch(k -> k.endsWith(":hours") || k.endsWith(":capacity"));
        withdraw(atClose);

        UUID late = asked(requestId, details("Hours Gala", "09:00", "18:01", 201));
        assertThat(attention(late)).anyMatch(k -> k.endsWith(":hours")).anyMatch(k -> k.endsWith(":capacity"));
        withdraw(late);

        String overnight = details("Hours Gala", "22:00", "23:00", 150)
                .replace("\"endDatetime\":\"2027-03-10T23:00", "\"endDatetime\":\"2027-03-11T02:00");
        UUID night = asked(requestId, overnight);
        assertThat(attention(night)).anyMatch(k -> k.endsWith(":hours"));
    }

    @Test
    @DisplayName("EC-NEW3 AC9 (conflict): a venue recorded as unavailable at the new time needs attention")
    void unavailabilityNeedsAttention() throws Exception {
        UUID venueId = venue("Repair Hall", 300, ALL_WEEK, 0, 0);
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Repair Gala", 150, "none"));
        bookedAndApproved(eventId, venueId, "ec1");
        jdbc.update("INSERT INTO venue_unavailability (unavailability_id, venue_id, start_datetime, end_datetime, reason, created_by) "
                + "VALUES (?, ?, ?::timestamptz, ?::timestamptz, 'Repainting', ?)", UUID.randomUUID(), venueId,
                DAY + "13:00:00+08:00", DAY + "16:00:00+08:00", flow.idOf("vs1"));
        sync();

        UUID id = asked(requestOf(eventId), details("Repair Gala", "09:00", "14:00", 150));

        assertThat(attention(id)).anyMatch(k -> k.endsWith(":unavailable"));
        assertThat(view(id, "ec1").get("impact").toString()).contains("Repainting");
    }

    // ---- EC-NEW3: deciding ---------------------------------------------------

    @Test
    @DisplayName("EC-NEW3 AC6, AC23, AC24, AC26, AC29, AC31, AC32, AC33, AC34, AC37, AC38, EO03 AC10, AC39, AC40 (happy): approving a confirmed event's new time needs every impact acknowledged, applies it, leaves the booking for the coordinator to move, returns it to Planning and keeps follow-ups")
    void approvingAConfirmedEventsNewTime() throws Exception {
        UUID venueId = venue("Main Hall", 300, ALL_WEEK, 0, 0);
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Moved Gala", 150, "none"));
        UUID bookingId = bookedAndApproved(eventId, venueId, "ec1");
        jdbc.update("UPDATE events SET status = 'confirmed', confirmed_at = now(), first_confirmed_at = now() WHERE event_id = ?", eventId);
        sync();
        UUID requestId = requestOf(eventId);
        UUID id = asked(requestId, details("Moved Gala", "10:00", "13:00", 150));

        List<String> needed = attention(id);
        // The booking is for 09:00-12:00 and is not moved automatically (AC32).
        assertThat(needed).contains("safety:replan", "venue:" + bookingId + ":move");
        // Without acknowledging: refused with the current impact.
        approve("ec1", id, List.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.impact[?(@.key == 'safety:replan')]").isNotEmpty());
        assertThat(eventField(eventId, "status")).isEqualTo("confirmed");

        approve("ec1", id, needed).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.decidedByName").value("ec1"))
                .andExpect(jsonPath("$.followUps[?(@.key == 'safety:replan')]").isNotEmpty());

        sync();
        assertThat(jdbc.queryForObject("SELECT start_datetime = '2027-03-10T10:00:00+08:00'::timestamptz FROM events "
                + "WHERE event_id = ?", Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT start_datetime = '2027-03-10T10:00:00+08:00'::timestamptz "
                + "FROM event_requests WHERE request_id = ?", Boolean.class, requestId)).isTrue();
        // Only the details change: not the organisation or the coordinator (EO03 AC10).
        assertThat(jdbc.queryForObject("SELECT organisation FROM events WHERE event_id = ?", String.class, eventId))
                .isEqualTo("Acme Pte Ltd");
        assertThat(eventField(eventId, "coordinator_id")).isEqualTo(flow.idOf("ec1").toString());
        // The booking keeps its old window until someone moves it (AC32).
        assertThat(jdbc.queryForObject("SELECT start_datetime = '2027-03-10T09:00:00+08:00'::timestamptz "
                + "FROM venue_bookings WHERE booking_id = ?", Boolean.class, bookingId)).isTrue();
        assertThat(eventField(eventId, "status")).isEqualTo("pending");
        List<String> log = new ArrayList<>();
        flow.read(mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("eo1")))
                .andReturn().getResponse().getContentAsString()).forEach(e -> log.add(
                        e.get("type").asString() + ":" + e.get("significance").asString()));
        assertThat(log).containsSubsequence("change_requested:major", "change_approved:major", "returned_to_planning:major");
    }

    @Test
    @DisplayName("EC-NEW3 AC35 (happy): a change to the name only keeps a confirmed event confirmed, with nothing to acknowledge")
    void aMinorChangeKeepsTheStatus() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Old Name Gala", 150, "none"));
        sync();
        jdbc.update("UPDATE events SET status = 'confirmed', confirmed_at = now(), first_confirmed_at = now() WHERE event_id = ?", eventId);
        sync();
        UUID id = asked(requestOf(eventId), details("New Name Gala", "09:00", "12:00", 150));

        assertThat(attention(id)).isEmpty();
        approve("ec1", id, List.of()).andExpect(status().isOk());

        assertThat(eventField(eventId, "status")).isEqualTo("confirmed");
        assertThat(eventField(eventId, "event_name")).isEqualTo("New Name Gala");
    }

    @Test
    @DisplayName("EC-NEW3 AC24, AC25, AC36, AC39 (negative): rejecting needs a reason; a rejection changes nothing and tells the organiser why")
    void rejecting() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Kept Gala", 150, "none"));
        UUID id = asked(requestOf(eventId), details("Kept Gala", "09:00", "12:00", 400));

        mvc.perform(post("/api/change-requests/" + id + "/reject").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/change-requests/" + id + "/reject").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No hall holds 400\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisionReason").value("No hall holds 400"));

        assertThat(eventField(eventId, "expected_attendance")).isEqualTo("150");
        assertThat(eventField(eventId, "status")).isEqualTo("pending");
        approve("ec1", id, List.of()).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("EC-NEW3 AC27, AC28 (conflict): a proposal whose start has since passed, or an event that has since started, cannot be approved")
    void timePassingBlocksApproval() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Late Gala", 150, "none"));
        UUID id = asked(requestOf(eventId), details("Late Gala", "09:00", "12:00", 160));

        jdbc.update("UPDATE event_change_requests SET proposed_values = jsonb_set(proposed_values, '{startDatetime}', "
                + "'\"2026-01-01T09:00:00+08:00\"'::jsonb) WHERE change_request_id = ?", id);
        sync();
        approve("ec1", id, List.of()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.startDatetime").exists());

        jdbc.update("UPDATE events SET start_datetime = now() - interval '1 minute' WHERE event_id = ?", eventId);
        sync();
        approve("ec1", id, List.of()).andExpect(status().isConflict());
        JsonNode closed = view(id, "eo1");
        assertThat(closed.get("closedReason").asString()).isEqualTo("The event has already started, so it can no longer be changed.");
    }

    @Test
    @DisplayName("EC-NEW3 AC42, EO03 AC33 (conflict): when the Lead reassigns the event, the pending change moves to the new coordinator")
    void reassignmentMovesTheDecision() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Handed Gala", 150, "none"));
        UUID requestId = requestOf(eventId);
        UUID id = asked(requestId, details("Handed Gala", "09:00", "12:00", 160));

        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());
        sync();

        approve("ec1", id, List.of()).andExpect(status().isForbidden());
        approve("ec2", id, List.of()).andExpect(status().isOk());
    }

    private String timelineMessage(UUID requestId, String type) {
        return jdbc.queryForObject("SELECT message FROM event_request_activity WHERE request_id = ? AND activity_type = ? "
                + "ORDER BY occurred_at DESC LIMIT 1", String.class, requestId, type);
    }

    @Test
    @DisplayName("EO03 AC12, EC-NEW3 AC25, AC38 (boundary): a 2,000-character reason or rejection reason, and approving a long description, are saved with the timeline entry cut to 2,000 characters")
    void longTimelineMessagesAreCutToFit() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Long Gala", 150, "none"));
        UUID requestId = requestOf(eventId);

        // Reason (2,000) + " — " + field summary is over the timeline's 2,000-character limit.
        ask("eo1", requestId, "y".repeat(2000), details("Long Gala", "09:00", "12:00", 160))
                .andExpect(status().isCreated());
        sync();
        String asked = timelineMessage(requestId, "change_requested");
        assertThat(asked).hasSize(2000).startsWith("y".repeat(1999)).endsWith("…");
        UUID first = jdbc.queryForObject("SELECT change_request_id FROM event_change_requests WHERE request_id = ? "
                + "AND status = 'pending'", UUID.class, requestId);

        // "Rejected: " + a 2,000-character reason.
        mvc.perform(post("/api/change-requests/" + first + "/reject").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "n".repeat(2000) + "\"}"))
                .andExpect(status().isOk());
        sync();
        assertThat(timelineMessage(requestId, "change_rejected")).hasSize(2000).startsWith("Rejected: n").endsWith("…");

        // "Approved: Description: All hands → " + a 2,500-character description.
        String longDescription = "d".repeat(2500);
        UUID second = asked(requestId, details("Long Gala", "09:00", "12:00", 150)
                .replace("\"All hands\"", "\"" + longDescription + "\""));
        approve("ec1", second, attention(second)).andExpect(status().isOk());
        sync();
        assertThat(timelineMessage(requestId, "change_approved")).hasSize(2000).startsWith("Approved: Description:").endsWith("…");
        assertThat(eventField(eventId, "description")).isEqualTo(longDescription);
    }

    @Test
    @DisplayName("EC-NEW3 AC29, AC31 (conflict): a change cannot be approved once the request has changed since it was asked for, so a clarification's edits are never undone and nothing is applied")
    void approvalRefusedWhenRequestChangedSinceAsked() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Moved Gala", 150, "none"));
        UUID id = asked(requestId, details("Moved Gala", "09:00", "12:00", 200));

        // While the change waits, a clarification is asked and answered with a later start.
        mvc.perform(post("/api/event-requests/" + requestId + "/clarifications").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Can it start later?\",\"flaggedFields\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/event-requests/" + requestId + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"Moved to 10:00.\",\"details\":" + details("Moved Gala", "10:00", "12:00", 150) + "}"))
                .andExpect(status().isOk());
        sync();

        approve("ec1", id, List.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This request has changed since the change was asked for. "
                        + "The organiser needs to withdraw it and ask again."));
        sync();
        // Nothing applied: the clarification's start stays, the attendance is not changed, the change still waits.
        assertThat(jdbc.queryForObject("SELECT start_datetime = '" + DAY + "10:00:00+08:00'::timestamptz "
                + "FROM event_requests WHERE request_id = ?", Boolean.class, requestId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM event_requests WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(150);
        assertThat(view(id, "eo1").get("status").asString()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE request_id = ? "
                + "AND activity_type = 'change_approved'", Integer.class, requestId)).isZero();

        // Withdrawn and asked again from the current details, it can be approved.
        withdraw(id);
        UUID again = asked(requestId, details("Moved Gala", "10:00", "12:00", 200));
        approve("ec1", again, attention(again)).andExpect(status().isOk());
        sync();
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM event_requests WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT start_datetime = '" + DAY + "10:00:00+08:00'::timestamptz "
                + "FROM event_requests WHERE request_id = ?", Boolean.class, requestId)).isTrue();
    }
}
