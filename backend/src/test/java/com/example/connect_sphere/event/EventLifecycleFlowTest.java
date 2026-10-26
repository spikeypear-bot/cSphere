package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
 * EC08, EO04 and EC-NEW2 end to end on real PostgreSQL through the real
 * filter chain: from an approved event, through a venue request Venue Staff
 * approve, safety review and confirmation, to what the organiser sees, and
 * back to Planning when the venue becomes unavailable. Every test rolls back.
 *
 * <p>Safety Officer decisions are recorded through the lifecycle service
 * directly: the Safety Officer's own screens and endpoints are SO-C4/C5/C6.
 */
@Tag("integration") @Tag("VS17") @Tag("SO-C2")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("EC08")
@Tag("EO04")
@Tag("EC-NEW2")
@Tag("SO-C4")
@Tag("VS-C1")
class EventLifecycleFlowTest {
    @Autowired com.example.connect_sphere.safety.ReadinessService venueReadiness;
    private void attestVenues(UUID eventId) {
        for(var item:venueReadiness.forSafetyOfficer(eventId).items())
            venueReadiness.updateItem(flow.idOf("so1"),eventId,item.itemKey(),new com.example.connect_sphere.safety.UpdateReadinessItemRequest(true,"Verified"));
    }

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;
    @Autowired EventLifecycleService lifecycle;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    /** An approved event (eo1, assigned to ec1) whose venue Venue Staff approved. */
    private UUID readyEvent() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
        UUID venueId = venues.createVenue(new CreateVenueDto("Lifecycle Hall " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "Open daily", null, List.of(), List.of(), ALL_WEEK)).venueId();
        entityManager.flush();
        String booking = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bookingId = flow.read(booking).get("bookingId").asString();
        mvc.perform(patch("/api/venue-bookings/" + bookingId + "/approve").with(flow.as("vs1")))
                .andExpect(status().isOk());
        return eventId;
    }

    private String eventStatus(UUID eventId) {
        sync();
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    @Test
    @DisplayName("EC08 AC16, AC25, AC29, EC-NEW2 AC2, AC18, EO04 AC6, AC8, AC12 (happy): the whole journey from Planning to Confirmed shows the same status everywhere")
    void theWholeJourneyFromPlanningToConfirmedShowsTheSameStatusEverywhere() throws Exception {
        UUID eventId = readyEvent();

        mvc.perform(get("/api/events/" + eventId + "/lifecycle").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSendForReview").value(true))
                .andExpect(jsonPath("$.journeyStatus.label").value("Planning"));

        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyStatus.code").value("AWAITING_SAFETY_REVIEW"))
                .andExpect(jsonPath("$.canConfirm").value(false));

        // The organiser sees the same status, and no arrangements yet.
        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("eo1")))
                .andExpect(jsonPath("$.journeyStatus.label").value("Awaiting Safety Review"))
                .andExpect(jsonPath("$.view").value("not_confirmed"))
                .andExpect(jsonPath("$.venues").isEmpty());

        attestVenues(eventId);
        lifecycle.recordSafetyDecision(flow.idOf("ecl1"), eventId, SafetyDecision.APPROVE, null);

        mvc.perform(post("/api/events/" + eventId + "/confirm").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.journeyStatus.label").value("Confirmed"));

        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("confirmed"))
                .andExpect(jsonPath("$.venues.length()").value(1))
                .andExpect(jsonPath("$.venues[0].noLongerAvailable").value(false))
                .andExpect(jsonPath("$.safetyApprovedAt").isNotEmpty())
                .andExpect(jsonPath("$.coordinatorName").value("ec1"));
    }

    @Test
    @DisplayName("EC08 AC25 (negative): an event cannot be confirmed without the safety check")
    void anEventCannotBeConfirmedWithoutTheSafetyCheck() throws Exception {
        UUID eventId = readyEvent();

        mvc.perform(post("/api/events/" + eventId + "/confirm").with(flow.as("ec1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Safety Officer")));
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("EC08 AC10, AC15 (failure): an event without an approved venue cannot be sent, and every blocker is listed")
    void anEventWithoutAnApprovedVenueCannotBeSentAndEveryBlockerIsListed() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));

        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("No venue has been booked yet."));
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("EC08 AC19, AC20 (happy): withdrawing needs a reason and returns the event to Planning")
    void withdrawingNeedsAReasonAndReturnsTheEventToPlanning() throws Exception {
        UUID eventId = readyEvent();
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());

        mvc.perform(post("/api/events/" + eventId + "/safety-review/withdraw").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/events/" + eventId + "/safety-review/withdraw").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Date is changing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyStatus.code").value("PLANNING"));
    }

    @Test
    @DisplayName("EC08 AC21, EO04 AC21, AC22, EC-NEW2 AC15 (conflict): a venue becoming unavailable after confirmation sends the event back for re-confirmation")
    void aVenueBecomingUnavailableAfterConfirmationSendsTheEventBackForReConfirmation() throws Exception {
        UUID eventId = readyEvent();
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());
        attestVenues(eventId);
        lifecycle.recordSafetyDecision(flow.idOf("ecl1"), eventId, SafetyDecision.APPROVE, null);
        mvc.perform(post("/api/events/" + eventId + "/confirm").with(flow.as("ec1"))).andExpect(status().isOk());

        // What VS-C1 records when a venue becomes unavailable, then its signal.
        sync();
        UUID bookingId = jdbc.queryForObject("SELECT booking_id FROM venue_bookings WHERE event_id = ?", UUID.class, eventId);
        UUID venueId = jdbc.queryForObject("SELECT venue_id FROM venue_bookings WHERE booking_id = ?", UUID.class, bookingId);
        UUID period = UUID.randomUUID();
        jdbc.update("INSERT INTO venue_unavailability (unavailability_id, venue_id, start_datetime, end_datetime, reason, created_by) "
                + "VALUES (?, ?, '2027-03-09T00:00:00Z', '2027-03-11T00:00:00Z', 'Flooding', ?)", period, venueId, flow.idOf("vs1"));
        jdbc.update("INSERT INTO venue_unavailability_bookings VALUES (?, ?)", period, bookingId);
        // The listener runs after the unavailability commits, in its own
        // transaction, so this rolled-back test calls what it does directly.
        lifecycle.returnToPlanning(eventId, flow.idOf("vs1"), "A venue for this event became unavailable: Flooding");

        assertThat(eventStatus(eventId)).isEqualTo("pending");
        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("eo1")))
                .andExpect(jsonPath("$.view").value("reconfirming"))
                .andExpect(jsonPath("$.venues[0].noLongerAvailable").value(true));
        mvc.perform(get("/api/events/" + eventId + "/timeline").with(flow.as("ec1")))
                .andExpect(jsonPath("$[-1].type").value("returned_to_planning"));
    }

    @Test
    @DisplayName("EO04 AC2, AC3 (cross-cutting): organisers of another organisation see nothing, and other roles are refused")
    void organisersOfAnotherOrganisationSeeNothingAndOtherRolesAreRefused() throws Exception {
        UUID eventId = readyEvent();

        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("eo3")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("ec1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/arrangements").with(flow.as("vs1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("EC08 AC1, AC2, EC-NEW2 AC25 (cross-cutting): only the assigned coordinator can see readiness or move the event, including by direct API call")
    void onlyTheAssignedCoordinatorCanSeeReadinessOrMoveTheEvent() throws Exception {
        UUID eventId = readyEvent();

        mvc.perform(get("/api/events/" + eventId + "/lifecycle").with(flow.as("ec2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("eo1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/events/" + eventId + "/lifecycle").with(flow.as("eo1"))).andExpect(status().isForbidden());
        assertThat(eventStatus(eventId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("EC-NEW2 AC18, AC22 (happy): the organiser's request list shows the same status as the coordinator")
    void theOrganisersRequestListShowsTheSameJourneyStatusAsTheCoordinator() throws Exception {
        UUID eventId = readyEvent();
        mvc.perform(post("/api/events/" + eventId + "/safety-review").with(flow.as("ec1"))).andExpect(status().isOk());

        mvc.perform(get("/api/event-requests").with(flow.as("eo1")))
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].journeyStatus.label")
                        .value(org.hamcrest.Matchers.hasItem("Awaiting Safety Review")));
        mvc.perform(get("/api/events/" + eventId).with(flow.as("ec1")))
                .andExpect(jsonPath("$.journeyStatus.label").value("Awaiting Safety Review"));
    }
}
