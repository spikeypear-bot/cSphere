package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

/**
 * EO-C1 through the real API, services and PostgreSQL: each way a confirmed
 * event's venue can change tells its organisers, once, in the change's own
 * transaction. The event runs 9:00 AM to 12:00 PM on 10 Mar 2027 (Singapore
 * time) and books its venue with a 30-minute setup, so the venue is occupied
 * from 8:30 AM. Notices are read from the notification outbox; every test
 * rolls back.
 */
@Tag("integration")
@Tag("EO-C1")
@Tag("VS-C1")
@Tag("VS25")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrganiserVenueNoticeFlowTest {

    private static final List<VenueOperatingHourDto> ALL_WEEK = IntStream.rangeClosed(1, 7)
            .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired VenueService venues;

    private FlowSupport flow;
    private String name;
    private UUID eventId;

    @BeforeEach
    void setUp() throws Exception {
        flow = new FlowSupport(mvc, users);
        name = "EO-C1 Gala " + UUID.randomUUID();
        eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody(name, 150, "none"));
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private String venue(String label) {
        return label + " " + UUID.randomUUID();
    }

    private UUID venueAt(String address) {
        // A 30-minute setup by default, so bookings here occupy the venue from 8:30 AM.
        UUID id = venues.createVenue(new CreateVenueDto(address, 300, List.of(VenueLayout.theatre), "Open daily", null,
                List.of(), List.of(), ALL_WEEK, java.math.BigDecimal.valueOf(30), java.math.BigDecimal.ZERO, address)).venueId();
        entityManager.flush();
        return id;
    }

    /** The coordinator books the venue (with its default setup), and Venue Staff approve it. */
    private UUID approvedBooking(UUID venueId) throws Exception {
        String created = mvc.perform(post("/api/events/" + eventId + "/venue-bookings").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":\"" + venueId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        UUID bookingId = UUID.fromString(flow.read(created).get("bookingId").asString());
        mvc.perform(patch("/api/venue-bookings/" + bookingId + "/approve").with(flow.as("vs1"))).andExpect(status().isOk());
        sync();
        return bookingId;
    }

    private void confirmed() {
        jdbc.update("UPDATE events SET status='confirmed', first_confirmed_at=now(), confirmed_at=now(), "
                + "safety_approved_at=now() WHERE event_id=?", eventId);
        sync();
    }

    /** Venue Staff record the venue unavailable (VS-C1), acknowledging whatever it affects. */
    private void unavailable(UUID venueId, String from, String until) throws Exception {
        String period = "\"startDateTime\":\"2027-03-10T%s:00+08:00\",\"endDateTime\":\"2027-03-10T%s:00+08:00\",\"reason\":\"Flooding\""
                .formatted(from, until);
        String preview = mvc.perform(post("/api/venues/" + venueId + "/unavailability/preview").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{" + period + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> affected = new java.util.ArrayList<>();
        flow.read(preview).get("affectedBookings").forEach(b -> affected.add("\"" + b.get("bookingId").asString() + "\""));
        mvc.perform(post("/api/venues/" + venueId + "/unavailability").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + period + ",\"acknowledgedBookingIds\":[" + String.join(",", affected)
                                + "],\"previewToken\":\"" + flow.read(preview).get("previewToken").asString() + "\"}"))
                .andExpect(status().isCreated());
        sync();
    }

    /** What organisers are told about this test's event, by recipient. */
    private List<Map<String, Object>> notices() {
        entityManager.flush();
        return jdbc.queryForList("""
                SELECT u.username AS recipient, o.payload->>'message' AS message, o.payload->>'link_path' AS link
                FROM notification_outbox o JOIN users u ON u.user_id = (o.payload->>'recipient_user_id')::uuid
                WHERE o.payload->>'event_id' = ? AND o.payload->>'type' = 'confirmed_venue_changed'
                ORDER BY u.username""", eventId.toString());
    }

    private String toEo1() {
        List<String> messages = notices().stream().filter(n -> "eo1".equals(n.get("recipient")))
                .map(n -> (String) n.get("message")).toList();
        assertThat(messages).as("notices to eo1").hasSize(1);
        return messages.get(0);
    }

    @Test
    @DisplayName("EO-C1 AC1, AC6, AC7, AC9, AC12 (boundary): unavailability overlapping only the setup tells every organiser of that organisation, once, with the venue, times, reason and what happens next; nobody else")
    void unavailabilityDuringSetupTellsTheOrganisers() throws Exception {
        String address = venue("Harbour Hall");
        approvedBooking(venueAt(address));
        confirmed();

        unavailable(venueIdOf(address), "08:00", "08:45");

        assertThat(notices()).extracting(n -> n.get("recipient")).containsExactly("eo1", "eo2");
        assertThat(toEo1())
                .startsWith("A venue for your event is unavailable from 10 Mar 2027, 8:00 AM to 10 Mar 2027, 8:45 AM (Singapore time).")
                .contains(address).contains("Reason: Flooding.").contains("being re-confirmed")
                .doesNotContain(eventId.toString());
    }

    @Test
    @DisplayName("EO-C1 AC10 (boundary): unavailability ending exactly when setup starts does not touch the event, so nobody is told")
    void unavailabilityTouchingTheSetupTellsNobody() throws Exception {
        String address = venue("Harbour Hall");
        approvedBooking(venueAt(address));
        confirmed();

        unavailable(venueIdOf(address), "07:00", "08:30");

        assertThat(notices()).isEmpty();
    }

    @Test
    @DisplayName("EO-C1 AC3 (negative): an event never confirmed is still being planned; its organiser is not told")
    void aNeverConfirmedEventStaysInternal() throws Exception {
        String address = venue("Harbour Hall");
        approvedBooking(venueAt(address));

        unavailable(venueIdOf(address), "08:00", "13:00");

        assertThat(notices()).isEmpty();
    }

    @Test
    @DisplayName("EO-C1 AC11, AC12 (happy): with two venues, only the affected one is named and the other is said to stay confirmed")
    void onlyTheAffectedVenueIsNamed() throws Exception {
        String main = venue("Harbour Hall");
        String breakout = venue("Marina Room");
        approvedBooking(venueAt(main));
        approvedBooking(venueAt(breakout));
        confirmed();

        unavailable(venueIdOf(main), "10:00", "11:00");

        assertThat(toEo1()).contains("Affected venue: " + main).doesNotContain(breakout)
                .contains("Its other venues stay as confirmed.");
    }

    @Test
    @DisplayName("EO-C1 AC1, AC5 (happy): the coordinator cancelling a confirmed booking tells the organiser, with the reason")
    void cancellingAConfirmedBookingTellsTheOrganiser() throws Exception {
        String address = venue("Harbour Hall");
        UUID booking = approvedBooking(venueAt(address));
        confirmed();

        mvc.perform(post("/api/events/" + eventId + "/venue-bookings/" + booking + "/cancel").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Client moved the session online\"}"))
                .andExpect(status().isOk());

        assertThat(toEo1()).startsWith("A confirmed venue booking for your event was cancelled.").contains(address)
                .contains("Reason: Client moved the session online.");
    }

    @Test
    @DisplayName("EO-C1 AC1, AC17 (happy): Venue Staff changing the booked setup time tells the organiser, but not the staff's internal note")
    void aTimingChangeTellsTheOrganiserWithoutTheStaffNote() throws Exception {
        String address = venue("Harbour Hall");
        UUID booking = approvedBooking(venueAt(address));
        confirmed();

        mvc.perform(put("/api/venue-bookings/" + booking + "/timing").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setupDurationMinutes\":60,\"turnaroundDurationMinutes\":0,\"timingNote\":\"Stage crew needs longer\","
                                + "\"previousSetupDurationMinutes\":30,\"previousTurnaroundDurationMinutes\":0}"))
                .andExpect(status().isOk());

        assertThat(toEo1()).startsWith("The venue's booked setup and turnaround times for your event changed.")
                .contains(address).doesNotContain("Stage crew");
    }

    @Test
    @DisplayName("EO-C1 AC1 (happy): an operational issue Venue Staff report during the event tells the organiser what it is")
    void anOperationalIssueTellsTheOrganiser() throws Exception {
        String address = venue("Harbour Hall");
        approvedBooking(venueAt(address));
        confirmed();

        mvc.perform(post("/api/venues/" + venueIdOf(address) + "/operational-issues").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Air-conditioning failure\",\"affectedFrom\":\"2027-03-10T10:00:00+08:00\","
                                + "\"affectedUntil\":\"2027-03-10T11:00:00+08:00\"}"))
                .andExpect(status().isCreated());

        assertThat(toEo1()).startsWith("Venue Staff reported an operational issue at a venue for your event.")
                .contains(address).contains("Reason: Air-conditioning failure.");
    }

    private UUID venueIdOf(String address) {
        return jdbc.queryForObject("SELECT venue_id FROM venues WHERE venue_address = ?", UUID.class, address);
    }
}
