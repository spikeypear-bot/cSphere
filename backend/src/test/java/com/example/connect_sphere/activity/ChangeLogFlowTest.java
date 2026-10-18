package com.example.connect_sphere.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

/**
 * EC-NEW5: the event change log, end to end through the real API, services,
 * listeners and PostgreSQL. Each action a person takes on an event adds one
 * entry, in the same transaction, saying who, in which role, what and when;
 * nothing can edit or delete an entry; each role reads only what concerns
 * it. Every test rolls back.
 */
@Tag("integration")
@Tag("EC-NEW5")
@Tag("EC03")
@Tag("EC07")
@Tag("VS02")
@Tag("VS-C1")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChangeLogFlowTest {

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

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

    private UUID venue(String name) {
        UUID id = venues.createVenue(new CreateVenueDto(name + " " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        return id;
    }

    private UUID requestVenue(UUID eventId, UUID venueId, UUID replaces) throws Exception {
        String body = replaces == null ? "{\"venueId\":\"" + venueId + "\"}"
                : "{\"venueId\":\"" + venueId + "\",\"replacesBookingId\":\"" + replaces + "\"}";
        String created = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(created).get("bookingId").asString());
    }

    private void approveVenue(UUID bookingId) throws Exception {
        mvc.perform(patch("/api/venue-bookings/" + bookingId + "/approve").with(flow.as("vs1")))
                .andExpect(status().isOk());
        sync();
    }

    private List<JsonNode> log(UUID eventId, String as) throws Exception {
        String body = mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<JsonNode> entries = new ArrayList<>();
        flow.read(body).forEach(entries::add);
        return entries;
    }

    private static List<String> types(List<JsonNode> entries) {
        return entries.stream().map(e -> e.get("type").asString()).toList();
    }

    private static JsonNode only(List<JsonNode> entries, String type) {
        List<JsonNode> matching = entries.stream().filter(e -> e.get("type").asString().equals(type)).toList();
        assertThat(matching).as(type).hasSize(1);
        return matching.get(0);
    }

    private int rows(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT count(*) FROM event_request_activity a JOIN event_requests r"
                + " ON r.request_id = a.request_id WHERE r.event_id = ?", Integer.class, eventId);
    }

    private UUID equipment(String name, int quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, ?, ?, false, 'A')
                """, id, name, quantity);
        return id;
    }

    @Test
    @DisplayName("EC-NEW5 AC1–AC6, AC8, AC10, AC12, AC15, AC17–AC19 (happy): venue and equipment decisions each add one entry, oldest first, with who, role, what and the reason")
    void venueAndEquipmentDecisionsAreLogged() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Change Log Gala", 150, "none"));
        UUID first = venue("Harbour Hall");
        UUID alternative = venue("Marina Hall");
        UUID extra = venue("Breakout Room");

        UUID rejected = requestVenue(eventId, first, null);
        mvc.perform(patch("/api/venue-bookings/" + rejected + "/reject").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Closed for repairs\",\"alternativeVenueId\":\"" + alternative + "\"}"))
                .andExpect(status().isOk());
        sync();
        approveVenue(requestVenue(eventId, alternative, null));
        requestVenue(eventId, extra, null);

        UUID mics = equipment("Wireless mic " + UUID.randomUUID(), 10);
        String created = mvc.perform(post("/api/events/" + eventId + "/equipment-request").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalRequirement\":\"Stage audio\",\"items\":[{\"equipmentId\":\"" + mics
                                + "\",\"quantity\":4}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        mvc.perform(patch("/api/equipment-requests/" + flow.read(created).get("requestId").asString() + "/status")
                        .with(flow.as("ts1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"rejected\",\"rejectReason\":\"All mics are booked\"}"))
                .andExpect(status().isOk());
        sync();

        List<JsonNode> log = log(eventId, "ec1");

        // AC1/AC5: one entry per action, in the order they happened.
        assertThat(types(log)).containsSubsequence("submitted", "coordinator_assigned", "approved",
                "venue_booking_requested", "venue_booking_rejected", "venue_alternative_suggested",
                "venue_booking_requested", "venue_booking_approved", "venue_added", "equipment_requested",
                "equipment_rejected");
        List<OffsetDateTime> times = log.stream().map(e -> OffsetDateTime.parse(e.get("occurredAt").asString())).toList();
        assertThat(times).isSortedAccordingTo(OffsetDateTime::compareTo);
        // AC2–AC4/AC6: who, in which role, and what they said.
        JsonNode venueRejection = only(log, "venue_booking_rejected");
        assertThat(venueRejection.get("actorName").asString()).isEqualTo("vs1");
        assertThat(venueRejection.get("actorRole").asString()).isEqualTo("vs");
        assertThat(venueRejection.get("message").asString()).contains("Harbour Hall").contains("Closed for repairs");
        assertThat(only(log, "venue_alternative_suggested").get("message").asString()).contains("Marina Hall");
        assertThat(only(log, "venue_booking_approved").get("message").asString()).contains("Marina Hall");
        JsonNode equipmentRequest = only(log, "equipment_requested");
        assertThat(equipmentRequest.get("actorName").asString()).isEqualTo("ec1");
        assertThat(equipmentRequest.get("message").asString()).contains("4");
        JsonNode equipmentRejection = only(log, "equipment_rejected");
        assertThat(equipmentRejection.get("actorRole").asString()).isEqualTo("technician");
        assertThat(equipmentRejection.get("message").asString()).contains("All mics are booked");
        // AC17/AC18: labelled on the server.
        assertThat(only(log, "venue_booking_rejected").get("significance").asString()).isEqualTo("major");
        assertThat(only(log, "venue_added").get("significance").asString()).isEqualTo("major");
        assertThat(only(log, "venue_booking_approved").get("significance").asString()).isEqualTo("normal");
        assertThat(only(log, "equipment_requested").get("significance").asString()).isEqualTo("normal");
    }

    /** Venue Staff record the venue as unavailable over the event (VS-C1), acknowledging the booking. */
    private void markUnavailable(UUID venueId, UUID booking) throws Exception {
        String period = "\"startDateTime\":\"2027-03-10T08:00:00+08:00\",\"endDateTime\":\"2027-03-10T13:00:00+08:00\","
                + "\"reason\":\"Flooding\"";
        String preview = mvc.perform(post("/api/venues/" + venueId + "/unavailability/preview").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{" + period + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = flow.read(preview).get("previewToken").asString();
        mvc.perform(post("/api/venues/" + venueId + "/unavailability").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + period + ",\"acknowledgedBookingIds\":[\"" + booking + "\"],\"previewToken\":\""
                                + token + "\"}"))
                .andExpect(status().isCreated());
        sync();
    }

    @Test
    @DisplayName("EC-NEW5 AC7, AC9, AC18, AC21 (happy): the organiser's edits and reply are one resubmission entry, Major when attendance changes")
    void theOrganisersResubmissionIsLogged() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Reply Gala", 150, "none"));
        mvc.perform(post("/api/event-requests/" + requestId + "/clarifications").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Is 150 final?\",\"flaggedFields\":[\"expectedAttendance\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/event-requests/" + requestId).with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                        .content(FlowSupport.requestBody("Reply Gala", 120, "none")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/event-requests/" + requestId + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"120 confirmed.\"}"))
                .andExpect(status().isOk());
        String approved = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        sync();

        List<JsonNode> log = log(UUID.fromString(flow.read(approved).get("eventId").asString()), "ec1");
        assertThat(types(log)).containsExactly("submitted", "coordinator_assigned", "clarification_requested",
                "clarification_responded", "approved");
        JsonNode reply = only(log, "clarification_responded");
        assertThat(reply.get("actorName").asString()).isEqualTo("eo1");
        assertThat(reply.get("message").asString()).isEqualTo("120 confirmed.");
        // The previous value is on the request for clarification, the new one here (AC21).
        assertThat(only(log, "clarification_requested").get("fieldValues").get("expectedAttendance").asInt()).isEqualTo(150);
        assertThat(reply.get("fieldValues").get("expectedAttendance").asInt()).isEqualTo(120);
        assertThat(reply.get("significance").asString()).isEqualTo("major");
    }

    @Test
    @DisplayName("EC-NEW5 AC13 (happy): sending for the safety check and withdrawing it are each logged, with the reason")
    void theSafetyReviewIsLogged() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Safety Gala", 150, "none"));
        approveVenue(requestVenue(eventId, venue("Safe Hall"), null));

        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());
        mvc.perform(post("/api/events/" + eventId + "/safety-review/withdraw").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Date is changing\"}"))
                .andExpect(status().isOk());
        sync();

        List<JsonNode> log = log(eventId, "ec1");
        assertThat(types(log)).containsSubsequence("venue_booking_approved", "sent_for_safety_review",
                "safety_review_withdrawn");
        assertThat(only(log, "safety_review_withdrawn").get("message").asString()).contains("Date is changing");
    }

    @Test
    @DisplayName("EC-NEW5 AC10 (happy): replacing a venue that became unavailable is its own entry, distinct from adding another venue")
    void replacingAVenueIsLoggedAsAReplacement() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Replacement Gala", 150, "none"));
        UUID oldHall = venue("Old Hall");
        UUID original = requestVenue(eventId, oldHall, null);
        approveVenue(original);
        markUnavailable(oldHall, original);

        requestVenue(eventId, venue("New Hall"), original);

        List<JsonNode> log = log(eventId, "ec1");
        assertThat(types(log)).contains("venue_booking_replaced").doesNotContain("venue_added");
        assertThat(only(log, "venue_booking_replaced").get("actorName").asString()).isEqualTo("ec1");
    }

    @Test
    @DisplayName("EC-NEW5 AC11, AC18 (conflict): Venue Staff recording a booked venue as unavailable adds a Major entry with the reason")
    void aVenueBecomingUnavailableIsLogged() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Flooded Gala", 150, "none"));
        UUID venueId = venue("Low Hall");
        UUID booking = requestVenue(eventId, venueId, null);
        approveVenue(booking);

        markUnavailable(venueId, booking);

        JsonNode entry = only(log(eventId, "ec1"), "venue_unavailable");
        assertThat(entry.get("actorName").asString()).isEqualTo("vs1");
        assertThat(entry.get("message").asString()).contains("Flooding");
        assertThat(entry.get("significance").asString()).isEqualTo("major");
    }

    @Test
    @DisplayName("EC-NEW5 AC16 (failure): an action that is refused adds no entry")
    void aRefusedActionAddsNoEntry() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Refused Gala", 150, "none"));
        UUID booking = requestVenue(eventId, venue("Quiet Hall"), null);
        int before = rows(eventId);

        // A rejection without a reason is refused before anything changes.
        mvc.perform(patch("/api/venue-bookings/" + booking + "/reject").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().is4xxClientError());
        // ec2 is not assigned, so their request is refused too (ECL-C7).
        mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venue("Other Hall") + "\"}"))
                .andExpect(status().isForbidden());

        assertThat(rows(eventId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EC-NEW5 AC26 (negative): the database refuses to edit a change log entry")
    void entriesCannotBeEdited() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Locked Gala", 150, "none"));
        sync();

        assertThatThrownBy(() -> jdbc.update("UPDATE event_request_activity SET message = 'rewritten' WHERE request_id ="
                + " (SELECT request_id FROM event_requests WHERE event_id = ?)", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("EC-NEW5 AC26 (negative): the database refuses to delete a change log entry")
    void entriesCannotBeDeleted() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Kept Gala", 150, "none"));
        sync();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM event_request_activity WHERE request_id ="
                + " (SELECT request_id FROM event_requests WHERE event_id = ?)", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("EC-NEW5 AC29, AC30, AC31 (cross-cutting): the organiser sees only what concerns them, the Lead sees everything, and other coordinators are refused")
    void eachRoleSeesWhatConcernsIt() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Audience Gala", 150, "none"));
        approveVenue(requestVenue(eventId, venue("Shared Hall"), null));

        List<String> coordinator = types(log(eventId, "ec1"));
        List<String> organiser = types(log(eventId, "eo1"));
        List<String> lead = types(log(eventId, "ecl1"));

        assertThat(coordinator).contains("venue_booking_requested", "venue_booking_approved");
        assertThat(organiser).contains("submitted", "approved")
                .doesNotContain("venue_booking_requested", "venue_booking_approved");
        assertThat(lead).containsExactlyElementsOf(coordinator);
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec2"))).andExpect(status().isForbidden());
        // Organisers are scoped by organisation (AU04): eo3 is Globex, eo1 is Acme.
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("eo3"))).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("EC-NEW5 AC8, AC18, AC28, AC29 (conflict): after reassignment the new coordinator sees the whole history, including the Major reassignment")
    void aNewCoordinatorSeesTheWholeHistory() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Handover Gala", 150, "none"));
        requestVenue(eventId, venue("Handover Hall"), null);
        UUID requestId = jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id = ?", UUID.class, eventId);

        mvc.perform(post("/api/event-requests/assigned/" + requestId + "/reassign").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + flow.idOf("ec2") + "\"}"))
                .andExpect(status().isOk());
        sync();

        List<JsonNode> log = log(eventId, "ec2");
        assertThat(types(log)).containsSubsequence("submitted", "approved", "venue_booking_requested",
                "coordinator_reassigned");
        JsonNode reassigned = only(log, "coordinator_reassigned");
        assertThat(reassigned.get("actorRole").asString()).isEqualTo("ecl");
        assertThat(reassigned.get("significance").asString()).isEqualTo("major");
        // The previous coordinator can no longer open it (AC29, ECL-C7).
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1"))).andExpect(status().isForbidden());
    }
}
