package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto;
import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto.AssignedRequest;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * ECL-C2 against a real PostgreSQL database: which requests the Lead's
 * assignments list holds, under whom, and in what order. Requests are
 * created through the API (submit, assign, decide); each test rolls back.
 *
 * <p>Assertions look only at the requests a test created, so they hold on a
 * database that already has other requests for the seeded coordinators.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssignedRequestsQueryTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EventRequestService service;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    /** A complete request body for an event on the given day of March 2027. */
    private static String eventOn(int dayOfMarch) {
        return """
                {"eventName":"Event on %1$02d March","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-%1$02dT09:00:00+08:00","endDatetime":"2027-03-%1$02dT12:00:00+08:00",
                 "expectedAttendance":150,"venueRequirements":"Theatre seating",
                 "accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(dayOfMarch);
    }

    /** A submitted request for that day, assigned to {@code coordinator}. */
    private UUID assignedOn(int dayOfMarch, String organiser, String coordinator) throws Exception {
        return flow.submittedAndAssigned(organiser, coordinator, eventOn(dayOfMarch));
    }

    private void act(String username, String path, String body) throws Exception {
        mvc.perform(post("/api/event-requests/" + path).with(flow.as(username))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** Direct SQL, for the states no endpoint reaches (a cancelled request,
     * a draft with a coordinator). Clears the persistence context so the
     * next read comes from the database. */
    private void update(String sql, Object... args) {
        entityManager.flush();
        jdbc.update(sql, args);
        entityManager.clear();
    }

    private CoordinatorAssignmentsDto assignmentsOf(String coordinator) {
        return service.assignedRequests().stream()
                .filter(a -> a.coordinatorName().equals(coordinator)).findFirst().orElseThrow();
    }

    /** The coordinator's requests, narrowed to the ones this test created. */
    private List<UUID> listedOf(String coordinator, UUID... created) {
        Set<UUID> mine = Set.of(created);
        return assignmentsOf(coordinator).requests().stream()
                .map(r -> r.request().requestId()).filter(mine::contains).toList();
    }

    /** Every listed request, under any coordinator. */
    private List<UUID> listedAnywhere() {
        return service.assignedRequests().stream().flatMap(a -> a.requests().stream())
                .map(r -> r.request().requestId()).toList();
    }

    @Test
    void aRequestIsListedUnderItsCoordinatorWhileSubmittedOrWaitingOnClarificationAcrossOrganisations()
            throws Exception {
        UUID acmeSubmitted = assignedOn(10, "eo1", "ec1");
        UUID globexSentBack = assignedOn(11, "eo3", "ec1");
        UUID someoneElses = assignedOn(12, "eo1", "ec2");
        act("ec1", globexSentBack + "/clarifications", "{\"message\":\"Who is the event for?\"}");

        assertThat(listedOf("ec1", acmeSubmitted, globexSentBack, someoneElses))
                .containsExactly(acmeSubmitted, globexSentBack);
        assertThat(listedOf("ec2", acmeSubmitted, globexSentBack, someoneElses))
                .containsExactly(someoneElses);
        assertThat(assignmentsOf("ec1").requests())
                .filteredOn(r -> r.request().requestId().equals(globexSentBack))
                .extracting(r -> r.request().status().name(), r -> r.request().organisation())
                .containsExactly(tuple("clarification_required", "Globex Holdings"));
    }

    @Test
    void unassignedDraftApprovedRejectedAndCancelledRequestsAreNotListed() throws Exception {
        UUID stillUnderReview = assignedOn(10, "eo1", "ec1");
        UUID unassigned = flow.submitted("eo1", eventOn(11));
        UUID unassignedSentBack = flow.submitted("eo1", eventOn(12));
        act("ecl1", "unassigned/" + unassignedSentBack + "/clarifications", "{\"message\":\"Who is it for?\"}");
        UUID draft = flow.draft("eo1", eventOn(13));
        UUID approved = assignedOn(14, "eo1", "ec1");
        act("ec1", approved + "/approve", "{}");
        UUID rejected = assignedOn(15, "eo1", "ec1");
        act("ec1", rejected + "/reject", "{\"reason\":\"No capacity\"}");
        UUID cancelled = assignedOn(16, "eo1", "ec1");
        update("UPDATE event_requests SET status = 'cancelled' WHERE request_id = ?", cancelled);
        // A coordinator on a draft: only its status keeps it out.
        update("UPDATE event_requests SET coordinator_id = ? WHERE request_id = ?", flow.idOf("ec1"), draft);

        assertThat(listedAnywhere()).contains(stillUnderReview)
                .doesNotContain(unassigned, unassignedSentBack, draft, approved, rejected, cancelled);
    }

    @Test
    void withinACoordinatorRequestsAreOrderedByEventStartSoonestFirstWhateverOrderTheyWereAssignedIn()
            throws Exception {
        UUID middle = assignedOn(15, "eo1", "ec1");
        UUID someoneElses = assignedOn(8, "eo1", "ec2");
        UUID latest = assignedOn(20, "eo3", "ec1");
        UUID soonest = assignedOn(5, "eo1", "ec1");

        assertThat(listedOf("ec1", middle, someoneElses, latest, soonest))
                .containsExactly(soonest, middle, latest);
    }

    @Test
    void requestsThatStartAtTheSameMomentKeepOneFixedOrder() throws Exception {
        UUID first = assignedOn(10, "eo1", "ec1");
        UUID second = assignedOn(10, "eo1", "ec1");
        UUID third = assignedOn(10, "eo3", "ec1");
        entityManager.flush();
        List<UUID> byRequestId = jdbc.queryForList(
                "SELECT request_id FROM event_requests WHERE request_id IN (?, ?, ?) ORDER BY request_id",
                UUID.class, first, second, third);

        assertThat(listedOf("ec1", first, second, third)).isEqualTo(byRequestId);
    }

    @Test
    void everyCoordinatorIsListedInNameOrderIncludingOneWhoHoldsNothingAndNoOtherRole() {
        User newcomer = new User();
        newcomer.setUserId(UUID.randomUUID());
        newcomer.setUsername("a-new-coordinator");
        newcomer.setEmail("a-new-coordinator@connectsphere.test");
        newcomer.setHashedPassword("not-a-login");
        newcomer.setRole(UserRole.ec);
        newcomer.setOrganisation("ConnectSphere");
        users.saveAndFlush(newcomer);

        List<CoordinatorAssignmentsDto> assignments = service.assignedRequests();

        List<String> names = assignments.stream().map(CoordinatorAssignmentsDto::coordinatorName).toList();
        assertThat(names).contains("a-new-coordinator", "ec1", "ec2", "ec3", "ec4", "ec5")
                .doesNotContain("ecl1", "eo1", "vs1", "ts1", "att1")
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(assignmentsOf("a-new-coordinator").coordinatorId()).isEqualTo(newcomer.getUserId());
        assertThat(assignmentsOf("a-new-coordinator").requests()).isEmpty();
    }

    @Test
    void theNumberEachCoordinatorHoldsFollowsAssignmentAndDecisions() throws Exception {
        int ec1Before = assignmentsOf("ec1").requests().size();
        int ec2Before = assignmentsOf("ec2").requests().size();

        UUID first = assignedOn(10, "eo1", "ec1");
        assignedOn(11, "eo3", "ec1");

        assertThat(assignmentsOf("ec1").requests()).hasSize(ec1Before + 2);
        assertThat(assignmentsOf("ec2").requests()).hasSize(ec2Before);

        act("ec1", first + "/approve", "{}");

        assertThat(assignmentsOf("ec1").requests()).hasSize(ec1Before + 1);
        assertThat(listedOf("ec1", first)).isEmpty();
    }

    @Test
    void eachRequestCarriesTheDateItWasSubmittedNotWhenItWasAssignedOrCreated() throws Exception {
        UUID id = assignedOn(10, "eo1", "ec1");
        // Drafted nine days ago and last touched tomorrow: neither is the submission.
        update("UPDATE event_requests SET created_at = now() - interval '9 days', "
                + "updated_at = now() + interval '1 day' WHERE request_id = ?", id);
        OffsetDateTime submitted = jdbc.queryForObject(
                "SELECT occurred_at FROM event_request_activity WHERE request_id = ? AND activity_type = 'submitted'",
                OffsetDateTime.class, id);

        AssignedRequest shown = assignmentsOf("ec1").requests().stream()
                .filter(r -> r.request().requestId().equals(id)).findFirst().orElseThrow();

        assertThat(shown.submittedAt().toInstant()).isEqualTo(submitted.toInstant());
        assertThat(shown.submittedAt().toInstant()).isNotEqualTo(shown.request().updatedAt().toInstant());
        assertThat(shown.submittedAt().toInstant()).isNotEqualTo(shown.request().createdAt().toInstant());
    }

    @Test
    void readingTheListLeavesEveryRequestTimelineAndNotificationAsItWas() throws Exception {
        UUID id = assignedOn(10, "eo1", "ec1");
        entityManager.flush();
        String requestsBefore = snapshot("SELECT request_id, status, coordinator_id, updated_at FROM event_requests");
        String timelineBefore = snapshot("SELECT activity_id FROM event_request_activity");
        String notificationsBefore = snapshot("SELECT notification_id FROM notifications");

        assertThat(listedOf("ec1", id)).containsExactly(id);

        entityManager.flush();
        assertThat(snapshot("SELECT request_id, status, coordinator_id, updated_at FROM event_requests"))
                .isEqualTo(requestsBefore);
        assertThat(snapshot("SELECT activity_id FROM event_request_activity")).isEqualTo(timelineBefore);
        assertThat(snapshot("SELECT notification_id FROM notifications")).isEqualTo(notificationsBefore);
    }

    private String snapshot(String select) {
        return jdbc.queryForObject(
                "SELECT coalesce(string_agg(t::text, '|' ORDER BY t::text), '') FROM (" + select + ") t",
                String.class);
    }
}
