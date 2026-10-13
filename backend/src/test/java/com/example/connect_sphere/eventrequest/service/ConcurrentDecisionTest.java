package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.service.VenueBookingRequestService;

/**
 * Two identical actions at the same instant, each in its own real
 * transaction. The row locks (findForUpdate) must let exactly one through.
 * This is the one test class that commits, because a lock only matters
 * between transactions that both reach the database.
 *
 * <p>Its rows cannot be cleaned up afterwards (the request timeline is
 * append-only by design), so it writes them under an organisation no dev
 * account belongs to: nobody sees them in any console.
 */
@SpringBootTest
class ConcurrentDecisionTest {

    private static final String HIDDEN_ORG = "Automated Concurrency Tests";

    @Autowired EventRequestService requests;
    @Autowired VenueBookingRequestService bookings;
    @Autowired VenueService venues;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID id(String username) {
        return users.findByUsername(username).orElseThrow().getUserId();
    }

    private UUID submittedAndAssigned(UUID coordinator) {
        UUID creator = id("eo5");
        OffsetDateTime start = OffsetDateTime.parse("2028-01-15T09:00:00+08:00");
        UUID requestId = requests.saveNewDraft(HIDDEN_ORG, creator, new SaveEventRequestRequest(
                "Race " + UUID.randomUUID(), "Concurrency check", null, start, start.plusHours(2), 40,
                "Any room", null, List.of(AccessibilityFeature.none), false)).requestId();
        requests.submit(HIDDEN_ORG, creator, requestId);
        // No creator from here on, so no organiser's notification bell
        // receives these automated requests.
        jdbc.update("UPDATE event_requests SET created_by = NULL WHERE request_id = ?", requestId);
        requests.assignCoordinator(id("ecl1"), requestId, coordinator); // ELC-C6: the Lead assigns
        return requestId;
    }

    /** Runs {@code action} twice at once; returns how many calls succeeded. */
    private int succeededOf(Callable<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
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
            } catch (java.util.concurrent.ExecutionException refused) {
                // expected for the loser
            }
        }
        pool.shutdown();
        return ok;
    }

    @Test
    void twoSimultaneousApprovalsCreateExactlyOneEvent() throws Exception {
        UUID coordinator = id("ec5");
        UUID requestId = submittedAndAssigned(coordinator);

        int succeeded = succeededOf(() -> requests.approve(coordinator, requestId));

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events e JOIN event_requests r ON r.event_id = e.event_id "
                + "WHERE r.request_id = ?", Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE request_id = ? "
                + "AND activity_type = 'approved'", Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void twoSimultaneousBookingRequestsForOneEventCreateExactlyOne() throws Exception {
        UUID coordinator = id("ec5");
        UUID requestId = submittedAndAssigned(coordinator);
        UUID eventId = requests.approve(coordinator, requestId).eventId();
        // Open all day, every day (V18: a venue with no operating hours cannot be booked).
        List<VenueOperatingHourDto> allWeek = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(day -> new VenueOperatingHourDto(day, LocalTime.of(0, 0), LocalTime.of(23, 59))).toList();
        UUID venueId = venues.createVenue(new CreateVenueDto("Concurrency Hall " + UUID.randomUUID(), 100,
                List.of(VenueLayout.classroom), "Daily", null, List.of(), List.of(), allWeek)).venueId();

        try {
            int succeeded = succeededOf(() -> bookings.submit(coordinator, eventId,
                    new SubmitVenueBookingRequest(venueId, null, null)));

            assertThat(succeeded).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_bookings WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        } finally {
            // Leave no pending request in the shared Venue Staff queue, pass or fail.
            jdbc.update("UPDATE venue_bookings SET status = 'cancelled' WHERE event_id = ?", eventId);
        }
    }
}
