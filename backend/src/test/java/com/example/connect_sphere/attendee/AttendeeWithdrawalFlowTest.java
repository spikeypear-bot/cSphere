package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.notification.service.NotificationDelivery;
import com.example.connect_sphere.notification.service.NotificationOutbox;
import com.example.connect_sphere.user.repository.UserRepository;

@SpringBootTest(properties = "notifications.retry-delay-ms=3600000")
@AutoConfigureMockMvc
@Tag("integration")
@Tag("AT08")
@Tag("AT04")
@Tag("AT01")
@Tag("AT03")
@Tag("EC-NEW4")
class AttendeeWithdrawalFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired AttendeeRegistrationRepository registrations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean NotificationDelivery delivery;
    @MockitoSpyBean NotificationOutbox outbox;
    private FlowSupport flow;
    private UUID eventId;
    private UUID registrationId;

    @BeforeEach
    void fixture() {
        flow = new FlowSupport(mvc, users);
        Event event = new Event();
        eventId = UUID.randomUUID();
        event.setEventId(eventId);
        event.setEventName("AT08 isolated API fixture");
        event.setPurpose("Withdrawal testing");
        event.setStartDatetime(OffsetDateTime.now().plusDays(2));
        event.setEndDatetime(OffsetDateTime.now().plusDays(3));
        event.setExpectedAttendance(2);
        event.setVenueRequirements("None");
        event.setOrganisation(users.findByUsername("eo1").orElseThrow().getOrganisation());
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.now().minusDays(1));
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(2);
        event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(1));
        events.saveAndFlush(event);
        registrationId = insert("att1", "confirmed");
        insert("att2", "confirmed");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id' = ?", eventId.toString());
        jdbc.update("DELETE FROM notifications WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM event_registrations WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM events WHERE event_id = ?", eventId);
    }

    @Test
    @DisplayName("AT08 AT04 AT01 (happy): committed withdrawal preserves history, releases one place and is visible on fresh reads")
    void persistsAndReturnsDetails() throws Exception {
        var original = row();
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        mvc.perform(post(path()).with(flow.as("att1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationId").value(registrationId.toString()))
                .andExpect(jsonPath("$.reference").value(original.get("reference")))
                .andExpect(jsonPath("$.registrationStatus").value("withdrawn"))
                .andExpect(jsonPath("$.eventStatus").value("confirmed"))
                .andExpect(jsonPath("$.withdrawnAt").isString())
                .andExpect(jsonPath("$.withdrawalAvailable").value(false));
        var after = row();
        assertThat(after.get("registered_at")).isEqualTo(original.get("registered_at"));
        assertThat(after.get("reference")).isEqualTo(original.get("reference"));
        assertThat(after.get("attendee_id")).isEqualTo(original.get("attendee_id"));
        OffsetDateTime timestamp = jdbc.queryForObject("SELECT withdrawn_at FROM event_registrations WHERE registration_id=?",
                OffsetDateTime.class, registrationId);
        assertThat(timestamp).isBetween(before, OffsetDateTime.now().plusSeconds(1));
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(1);
        assertReceipt();
        assertThat(registrations.countByEventIdAndStatus(eventId, "withdrawn")).isEqualTo(1);
        assertThat(events.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.confirmed);
        mvc.perform(get("/api/attendee/registrations/" + registrationId).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("withdrawn"))
                .andExpect(jsonPath("$.withdrawnAt").isString());
        mvc.perform(get("/api/attendee/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].registrationId").value(registrationId.toString()))
                .andExpect(jsonPath("$[0].withdrawnAt").isString());
        mvc.perform(post(path()).with(flow.as("att1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This registration has already been withdrawn."));
        assertThat(row()).isEqualTo(after);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(1);
        assertReceipt();
    }

    @Test
    @DisplayName("AT08 (negative): authentication, every other role and forged ownership cannot change a registration")
    void securityBoundaries() throws Exception {
        var original = row();
        mvc.perform(post(path())).andExpect(status().isUnauthorized());
        for (String role : List.of("eo1", "ec1", "ecl1", "vs1", "ts1", "so1")) {
            mvc.perform(post(path()).with(flow.as(role))).andExpect(status().isForbidden());
        }
        String foreign = mvc.perform(post(path()).with(flow.as("att2")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Registration unavailable."))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/attendee/registrations/" + UUID.randomUUID() + "/withdraw").with(flow.as("att2")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Registration unavailable."));
        assertThat(foreign).doesNotContain("att1", "CS-", "AT08 isolated API fixture");
        mvc.perform(post(path()).param("attendeeId", flow.idOf("att1").toString()).with(flow.as("att2")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/attendee/registrations/not-a-uuid/withdraw").with(flow.as("att1")))
                .andExpect(status().isBadRequest());
        assertThat(row()).isEqualTo(original);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
        assertThat(queued()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved"})
    @DisplayName("AT08 AT04 (happy): all re-confirmation stages still allow withdrawal")
    void reconfirmationAllowed(String state) throws Exception {
        jdbc.update("UPDATE events SET status=CAST(? AS event_status) WHERE event_id=?", state, eventId);
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("withdrawn"))
                .andExpect(jsonPath("$.eventStatus").value("reconfirming"));
        assertThat(events.findById(eventId).orElseThrow().getStatus().name()).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "completed", "started", "event_cancelled", "withdrawn"})
    @DisplayName("AT08 (negative): ineligible states return conflicts without changing registration or capacity")
    void ineligibleUnchanged(String state) throws Exception {
        if (state.equals("started")) {
            jdbc.update("UPDATE events SET start_datetime=now()-interval '1 minute' WHERE event_id=?", eventId);
        } else if (state.equals("event_cancelled") || state.equals("withdrawn")) {
            jdbc.update("UPDATE event_registrations SET status=? WHERE registration_id=?", state, registrationId);
        } else {
            jdbc.update("UPDATE events SET status=CAST(? AS event_status) WHERE event_id=?", state, eventId);
        }
        var original = row();
        long count = registrations.countByEventIdAndStatus(eventId, "confirmed");
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isConflict());
        assertThat(row()).isEqualTo(original);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(count);
        assertThat(queued()).isZero();
    }

    @Test
    @DisplayName("AT08 (conflict): simultaneous withdrawal requests commit one transition and return one conflict")
    void concurrentRequests() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> withdraw = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return mvc.perform(post(path()).with(flow.as("att1"))).andReturn().getResponse().getStatus();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(withdraw);
            var second = pool.submit(withdraw);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            start.countDown();
        }
        assertThat(row().get("status")).isEqualTo("withdrawn");
        assertThat(row().get("withdrawn_at")).isNotNull();
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(1);
        assertReceipt();
    }

    @Test
    @DisplayName("AT08 (failure): a transaction failure after withdrawal rolls back status, date and released capacity")
    void rollbackIsAtomic() {
        var original = row();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isOk());
            } catch (Exception ex) {
                throw new IllegalStateException("Withdrawal request failed", ex);
            }
            throw new IllegalStateException("Simulated transaction failure");
        })).hasMessage("Simulated transaction failure");
        assertThat(row()).isEqualTo(original);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
        assertThat(queued()).isZero();
        assertThat(receipts()).isZero();
    }

    @Test
    @DisplayName("AT08 (failure): failed outbox persistence returns a safe error and rolls back status, timestamp and capacity")
    void enqueueFailureRollsBack() throws Exception {
        var original = row();
        NotificationOutbox target = AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new DataAccessResourceFailureException("Database unavailable")).when(target).enqueue(any());
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Withdrawal could not be confirmed. Refresh your registration status and try again."));
        assertThat(row()).isEqualTo(original);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
        assertThat(queued()).isZero();
        assertThat(receipts()).isZero();
    }

    @Test
    @DisplayName("AT08 (failure): delivery failure preserves withdrawal and retries, including lost acknowledgements, deliver exactly one receipt")
    void deliveryFailureAndRetry() throws Exception {
        doThrow(new IllegalStateException("Delivery offline")).when(delivery).deliver(any());
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isOk());
        assertThat(row().get("status")).isEqualTo("withdrawn");
        assertThat(row().get("withdrawn_at")).isNotNull();
        assertThat(queued()).isEqualTo(1);
        assertThat(receipts()).isZero();
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(1);
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isConflict());
        doCallRealMethod().when(delivery).deliver(any());
        outbox.retry();
        outbox.retry();
        assertReceipt();
        jdbc.update("UPDATE notifications SET read_at=now() WHERE registration_id=?", registrationId);
        var delivered = jdbc.queryForMap("SELECT * FROM notifications WHERE registration_id=?", registrationId);
        jdbc.update("UPDATE notification_outbox SET delivered_at=NULL WHERE payload->>'registration_id'=?", registrationId.toString());
        outbox.retry();
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE registration_id=?", registrationId)).isEqualTo(delivered);
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    @DisplayName("AT08 AT01 AT03 AT04 (cross-cutting): re-registration gets a new identity and preserves the original withdrawal and receipt link")
    void reregisterPreservesHistory() throws Exception {
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isOk());
        var withdrawn = row();
        String body = mvc.perform(post("/api/attendee/events/" + eventId + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(flow.read(body).get("registrationId").asString()).isNotEqualTo(registrationId.toString());
        assertThat(flow.read(body).get("reference").asString()).isNotEqualTo(withdrawn.get("reference"));
        assertThat(row()).isEqualTo(withdrawn);
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(2);
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isConflict());
        assertReceipt();
        mvc.perform(get("/api/attendee/events/" + eventId).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("confirmed"))
                .andExpect(jsonPath("$.availablePlaces").value(0));
    }

    @Test
    @DisplayName("AT08 AT01 (conflict): releasing a place during re-confirmation does not bypass new-registration eligibility")
    void capacityDoesNotBypassReconfirmation() throws Exception {
        jdbc.update("UPDATE events SET status='pending' WHERE event_id=?", eventId);
        mvc.perform(post(path()).with(flow.as("att1"))).andExpect(status().isOk());
        mvc.perform(post("/api/attendee/events/" + eventId + "/registrations").with(flow.as("att1")))
                .andExpect(status().isConflict());
        assertThat(registrations.countByEventIdAndStatus(eventId, "confirmed")).isEqualTo(1);
        assertReceipt();
    }

    private int queued() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'registration_id'=? AND payload->>'type'='attendee_registration_withdrawn'",
                Integer.class, registrationId.toString());
    }

    private int receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE registration_id=? AND type::text='attendee_registration_withdrawn'",
                Integer.class, registrationId);
    }

    private void assertReceipt() throws Exception {
        assertThat(queued()).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT recipient_user_id,actor_name,actor_role FROM notifications WHERE registration_id=? AND type::text='attendee_registration_withdrawn'", registrationId))
                .containsEntry("recipient_user_id", flow.idOf("att1")).containsEntry("actor_name", "att1").containsEntry("actor_role", "attendee");
        mvc.perform(get("/api/notifications").with(flow.as("att1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'attendee_registration_withdrawn' && @.eventId == '" + eventId + "')].linkPath")
                        .value(org.hamcrest.Matchers.contains("/attendee/my-registrations/" + registrationId)));
    }

    private UUID insert(String username, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_registrations(registration_id,event_id,attendee_id,attendee_name,reference,registered_at,status)
                VALUES (?,?,?,?,?,now()-interval '1 day',?)
                """, id, eventId, flow.idOf(username), username, "CS-" + id, state);
        return id;
    }

    private String path() { return "/api/attendee/registrations/" + registrationId + "/withdraw"; }
    private java.util.Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM event_registrations WHERE registration_id=?", registrationId);
    }
}
