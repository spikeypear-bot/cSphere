package com.example.connect_sphere.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * SO-C5 / SO-C6 decisions in real, committed transactions: two at the same
 * moment, one that fails part-way, and the coordinator's notification (sent
 * only after commit). Like ConcurrentDecisionTest, its rows are written under
 * an organisation no dev account belongs to, with no organiser, and each
 * event is cancelled afterwards so it leaves every console.
 */
@Tag("integration")
@Tag("SO-C5")
@Tag("SO-C6")
@SpringBootTest
class SafetyDecisionCommitTest {

    private static final String HIDDEN_ORG = "Automated Concurrency Tests";

    @Autowired ReadinessService readiness;
    @Autowired EventRequestService requests;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    // Used to make a decision fail part-way: the timeline entry looks the
    // event's request up after the review and the status are written.
    @MockitoSpyBean EventRequestRepository eventRequests;

    private final List<UUID> created = new ArrayList<>();

    private UUID id(String username) {
        return users.findByUsername(username).orElseThrow().getUserId();
    }

    /** An approved event of ec5's, awaiting the safety check. */
    private UUID eventAwaitingReview() {
        UUID creator = id("eo5");
        OffsetDateTime start = OffsetDateTime.now().plusMonths(6);
        UUID requestId = requests.saveNewDraft(HIDDEN_ORG, creator, new SaveEventRequestRequest(
                "Safety race " + UUID.randomUUID(), "Concurrency check", null, start, start.plusHours(2), 40,
                "Any room", null, List.of(AccessibilityFeature.none), false)).requestId();
        requests.submit(HIDDEN_ORG, creator, requestId);
        jdbc.update("UPDATE event_requests SET created_by = NULL WHERE request_id = ?", requestId);
        requests.assignCoordinator(id("ecl1"), requestId, id("ec5"));
        UUID eventId = requests.approve(id("ec5"), requestId).eventId();
        // Venue and equipment arrangements are EC08's; only the review stage matters here.
        jdbc.update("UPDATE events SET status = 'awaiting_safety_review' WHERE event_id = ?", eventId);
        created.add(eventId);
        return eventId;
    }

    @AfterEach
    void leaveNothingVisible() {
        for (UUID eventId : created) {
            jdbc.update("DELETE FROM notifications WHERE event_id = ?", eventId);
            jdbc.update("UPDATE events SET status = 'cancelled' WHERE event_id = ?", eventId);
        }
    }

    private String eventStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT status::text FROM events WHERE event_id = ?", String.class, eventId);
    }

    private int count(String sql, UUID eventId) {
        return jdbc.queryForObject(sql, Integer.class, eventId);
    }

    /** Runs both actions at once; returns how many succeeded. */
    private int succeededOf(List<Callable<Object>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> calls = new ArrayList<>();
        for (Callable<Object> action : actions) {
            calls.add(pool.submit(() -> {
                go.await();
                return action.call();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Object> call : calls) {
            try {
                call.get(30, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException refused) {
                // expected for the loser
            }
        }
        pool.shutdown();
        return ok;
    }

    @Test
    @DisplayName("SO-C5 AC8, AC9, SO-C6 AC9, AC10 (conflict): a rejection and a request for changes at the same moment save one decision, one timeline entry and one notification")
    void twoDecisionsAtTheSameMomentSaveExactlyOne() throws Exception {
        UUID eventId = eventAwaitingReview();
        UUID officer = id("so1");

        int succeeded = succeededOf(List.of(
                () -> readiness.review(officer, eventId, new SafetyApprovalRequest("reject", "Exits blocked",
                        List.of("safety_operations"))),
                () -> readiness.review(officer, eventId, new SafetyApprovalRequest("request_changes", "Add a marshal",
                        List.of("safety_operations")))));

        assertThat(succeeded).isEqualTo(1);
        assertThat(eventStatus(eventId)).isEqualTo("safety_changes_requested");
        assertThat(count("SELECT count(*) FROM event_safety_reviews WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM event_request_activity WHERE event_id = ? "
                + "AND activity_type IN ('safety_rejected', 'safety_changes_requested')", eventId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("SO-C5 AC9, SO-C6 AC10 (happy): the coordinator is notified of a rejection with its reason")
    void theCoordinatorIsNotifiedOfARejectionWithItsReason() {
        UUID eventId = eventAwaitingReview();

        readiness.review(id("so1"), eventId, new SafetyApprovalRequest("reject", "Exits blocked", List.of("safety_operations")));

        assertThat(jdbc.queryForList("SELECT message FROM notifications WHERE event_id = ? AND recipient_user_id = ?",
                String.class, eventId, id("ec5")))
                .containsExactly("The Safety Officer rejected the safety arrangement: Exits blocked");
    }

    @Test
    @DisplayName("SO-C5 AC12, SO-C6 AC13 (failure): a decision that fails part-way changes nothing and sends no notification")
    void aDecisionThatFailsPartWayChangesNothing() {
        UUID eventId = eventAwaitingReview();
        doThrow(new IllegalStateException("timeline unavailable")).when(eventRequests).findByEventId(eventId);

        assertThatThrownBy(() -> readiness.review(id("so1"), eventId,
                new SafetyApprovalRequest("reject", "Exits blocked", List.of("safety_operations"))))
                .hasMessage("timeline unavailable");

        assertThat(eventStatus(eventId)).isEqualTo("awaiting_safety_review");
        assertThat(count("SELECT count(*) FROM event_safety_reviews WHERE event_id = ?", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM event_request_activity WHERE event_id = ? "
                + "AND activity_type = 'safety_rejected'", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isZero();
    }
}
