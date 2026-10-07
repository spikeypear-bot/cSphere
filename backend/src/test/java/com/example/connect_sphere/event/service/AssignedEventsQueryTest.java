package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.dto.EventDto;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * EC09 against a real PostgreSQL database: which rows a coordinator's list
 * holds, and in what order. Events are created through the API (submit,
 * assign, approve); each test rolls back.
 *
 * <p>Assertions look only at the events a test created, so they hold on a
 * database that already has other events for the seeded coordinators.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssignedEventsQueryTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EventService service;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    /** An approved event for {@code coordinator}, starting on the given day of March 2027. */
    private UUID eventOn(int dayOfMarch, String organiser, String coordinator) throws Exception {
        String body = """
                {"eventName":"Event on %1$02d March","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-%1$02dT09:00:00+08:00","endDatetime":"2027-03-%1$02dT12:00:00+08:00",
                 "expectedAttendance":150,"venueRequirements":"Theatre seating",
                 "accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(dayOfMarch);
        return flow.approvedEvent(organiser, coordinator, body);
    }

    /** Direct SQL, for the states and reassignment no endpoint offers inside
     * a rolled-back test (confirming notifies in its own transaction, which
     * cannot see this test's uncommitted event). Clears the persistence
     * context so the next read comes from the database. */
    private void update(String sql, Object... args) {
        entityManager.flush();
        jdbc.update(sql, args);
        entityManager.clear();
    }

    private void setStatus(UUID eventId, String status) {
        update("UPDATE events SET status = CAST(? AS event_status) WHERE event_id = ?", status, eventId);
    }

    /** The coordinator's list, narrowed to the events this test created. */
    private List<UUID> listedOf(String coordinator, UUID... created) {
        Set<UUID> mine = Set.of(created);
        return service.assignedEvents(flow.idOf(coordinator)).stream()
                .map(EventDto::eventId).filter(mine::contains).toList();
    }

    @Test
    void aCoordinatorSeesTheirOwnEventsAcrossOrganisationsAndNeverAnotherCoordinators() throws Exception {
        UUID acmeEvent = eventOn(10, "eo1", "ec1");
        UUID globexEvent = eventOn(11, "eo3", "ec1");
        UUID someoneElses = eventOn(12, "eo1", "ec2");

        assertThat(listedOf("ec1", acmeEvent, globexEvent, someoneElses))
                .containsExactly(acmeEvent, globexEvent);
        assertThat(listedOf("ec2", acmeEvent, globexEvent, someoneElses))
                .containsExactly(someoneElses);
    }

    @Test
    void pendingAndConfirmedEventsAreListedWhileCancelledAndCompletedAreNot() throws Exception {
        UUID pending = eventOn(10, "eo1", "ec1");
        UUID confirmed = eventOn(11, "eo1", "ec1");
        UUID cancelled = eventOn(12, "eo1", "ec1");
        UUID completed = eventOn(13, "eo1", "ec1");
        setStatus(confirmed, "confirmed");
        setStatus(cancelled, "cancelled");
        setStatus(completed, "completed");

        assertThat(listedOf("ec1", pending, confirmed, cancelled, completed))
                .containsExactly(pending, confirmed);
        assertThat(service.assignedEvents(flow.idOf("ec1")))
                .filteredOn(e -> e.eventId().equals(confirmed))
                .extracting(EventDto::status).containsExactly("confirmed");
    }

    @Test
    void eventsAreOrderedByStartDateSoonestFirstWhateverOrderTheyWereCreatedIn() throws Exception {
        UUID middle = eventOn(15, "eo1", "ec1");
        UUID latest = eventOn(20, "eo1", "ec1");
        UUID soonest = eventOn(5, "eo1", "ec1");

        assertThat(listedOf("ec1", middle, latest, soonest))
                .containsExactly(soonest, middle, latest);
    }

    @Test
    void aNewlyApprovedEventAppearsAndAReassignedOneMovesToItsNewCoordinator() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
        int before = service.assignedEvents(flow.idOf("ec1")).size();

        String approved = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID eventId = UUID.fromString(flow.read(approved).get("eventId").asString());

        assertThat(service.assignedEvents(flow.idOf("ec1"))).hasSize(before + 1);
        assertThat(listedOf("ec1", eventId)).containsExactly(eventId);
        assertThat(listedOf("ec2", eventId)).isEmpty();

        update("UPDATE events SET coordinator_id = ? WHERE event_id = ?", flow.idOf("ec2"), eventId);

        assertThat(listedOf("ec1", eventId)).isEmpty();
        assertThat(listedOf("ec2", eventId)).containsExactly(eventId);
    }

    /** The endpoint through the real filter chain: the list follows the token's subject. */
    @Test
    void overHttpEachCoordinatorGetsOnlyTheirOwnEventsAndOtherRolesAreRefused() throws Exception {
        UUID mine = eventOn(10, "eo1", "ec1");
        UUID theirs = eventOn(11, "eo1", "ec2");

        mvc.perform(get("/api/events/assigned").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + mine + "')].eventName").value("Event on 10 March"))
                .andExpect(jsonPath("$[?(@.eventId == '" + theirs + "')]").isEmpty());
        mvc.perform(get("/api/events/assigned").with(flow.as("ec2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + theirs + "')].eventName").value("Event on 11 March"))
                .andExpect(jsonPath("$[?(@.eventId == '" + mine + "')]").isEmpty());
        for (String other : List.of("ecl1", "eo1", "vs1")) {
            mvc.perform(get("/api/events/assigned").with(flow.as(other)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void readingTheListLeavesEveryEventAndNotificationAsItWas() throws Exception {
        UUID eventId = eventOn(10, "eo1", "ec1");
        entityManager.flush();
        String eventsBefore = snapshot("SELECT event_id, status, coordinator_id FROM events");
        String notificationsBefore = snapshot("SELECT notification_id FROM notifications");

        assertThat(listedOf("ec1", eventId)).containsExactly(eventId);

        entityManager.flush();
        assertThat(snapshot("SELECT event_id, status, coordinator_id FROM events")).isEqualTo(eventsBefore);
        assertThat(snapshot("SELECT notification_id FROM notifications")).isEqualTo(notificationsBefore);
    }

    private String snapshot(String select) {
        return jdbc.queryForObject(
                "SELECT coalesce(string_agg(t::text, '|' ORDER BY t::text), '') FROM (" + select + ") t",
                String.class);
    }
}
