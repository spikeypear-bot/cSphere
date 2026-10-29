package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationDelivery;
import com.example.connect_sphere.notification.service.NotificationOutbox;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@SpringBootTest(properties = "notifications.retry-delay-ms=3600000")
@AutoConfigureMockMvc
@Tag("integration")
@Tag("AT03")
@Tag("AT01")
@Tag("AT04")
class RegistrationConfirmationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired AttendeeRegistrationService registrations;
    @Autowired NotificationOutbox outbox;
    @Autowired NotificationService notifications;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean NotificationDelivery delivery;
    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM notification_outbox WHERE payload->>'event_id'=?", id.toString());
            jdbc.update("DELETE FROM notifications WHERE event_id=?", id);
            jdbc.update("DELETE FROM event_registrations WHERE event_id=?", id);
            jdbc.update("DELETE FROM events WHERE event_id=?", id);
        }
    }

    @Test
    @DisplayName("AT03 (failure): delivery failure preserves the saved registration and retries exactly one linked confirmation")
    void deliveryFailureAndRetry() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event();
        doThrow(new IllegalStateException("Simulated delivery outage")).when(delivery).deliver(any());
        String body = mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(flow.read(body).get("registrationId").asString());
        assertThat(count("event_registrations", event.getEventId())).isEqualTo(1);
        assertThat(count("notifications", event.getEventId())).isZero();
        assertThat(queued(event.getEventId())).isEqualTo(1);
        mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationId").value(id.toString()));
        assertThat(queued(event.getEventId())).isEqualTo(1);
        doCallRealMethod().when(delivery).deliver(any());
        outbox.retry(); outbox.retry();
        assertThat(count("notifications", event.getEventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=? AND delivered_at IS NULL", Integer.class, event.getEventId().toString())).isZero();
        mvc.perform(get("/api/notifications").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.eventId == '" + event.getEventId() + "')].linkPath")
                        .value(org.hamcrest.Matchers.contains("/attendee/my-registrations/" + id)));
        mvc.perform(get("/api/attendee/registrations/" + id).with(flow.as("att2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/attendee/registrations/" + id).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("confirmed"));
    }

    @Test
    @DisplayName("AT03 (conflict): simultaneous submissions by the same attendee create one registration and one confirmation")
    void concurrentSameAttendee() throws Exception {
        Event event = event();
        FlowSupport flow = new FlowSupport(mvc, users);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            String first = calls.get(0).get(30, TimeUnit.SECONDS), second = calls.get(1).get(30, TimeUnit.SECONDS);
            assertThat(flow.read(first).get("registrationId").asString()).isEqualTo(flow.read(second).get("registrationId").asString());
        }
        assertThat(count("event_registrations", event.getEventId())).isEqualTo(1);
        assertThat(count("notifications", event.getEventId())).isEqualTo(1);
        assertThat(queued(event.getEventId())).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ATTENDEE")
    @DisplayName("AT03 (failure): transaction rollback leaves no registration, outbox entry or success notification")
    void rollbackIsAtomic() {
        Event event = event();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            registrations.register(event.getEventId(), users.findByUsername("att1").orElseThrow().getUserId());
            tx.setRollbackOnly();
        });
        assertThat(count("event_registrations", event.getEventId())).isZero();
        assertThat(count("notifications", event.getEventId())).isZero();
        assertThat(queued(event.getEventId())).isZero();
    }

    @Test
    @DisplayName("AT03 (negative): rejected registration creates neither a record nor confirmation work")
    void rejectedRegistration() throws Exception {
        Event event = event(); event.setStatus(EventStatus.cancelled); events.saveAndFlush(event);
        mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(new FlowSupport(mvc, users).as("att1")))
                .andExpect(status().isConflict());
        assertThat(count("event_registrations", event.getEventId())).isZero();
        assertThat(count("notifications", event.getEventId())).isZero();
        assertThat(queued(event.getEventId())).isZero();
    }

    @Test
    @WithMockUser(roles = "ATTENDEE")
    @DisplayName("AT03 (boundary): duplicate enqueue calls use one stable notification identity per registration")
    void duplicateEnqueue() {
        Event event = event();
        var saved = registrations.register(event.getEventId(), users.findByUsername("att1").orElseThrow().getUserId());
        UUID owner = users.findByUsername("att1").orElseThrow().getUserId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            notifications.createAttendeeRegistrationConfirmation(owner, event.getEventId(), event.getEventName(), saved.reference(), saved.registrationId());
            notifications.createAttendeeRegistrationConfirmation(owner, event.getEventId(), event.getEventName(), saved.reference(), saved.registrationId());
        });
        assertThat(queued(event.getEventId())).isEqualTo(1);
        assertThat(count("notifications", event.getEventId())).isEqualTo(1);
    }

    private int count(String table, UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE event_id=?", Integer.class, eventId);
    }
    private int queued(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'event_id'=?", Integer.class, id.toString());
    }
    private Event event() {
        Event event = new Event(); event.setEventId(UUID.randomUUID());
        event.setEventName("AT03 confirmation"); event.setPurpose("Confirmation test");
        event.setStartDatetime(OffsetDateTime.now().plusDays(2)); event.setEndDatetime(OffsetDateTime.now().plusDays(3));
        event.setExpectedAttendance(2); event.setVenueRequirements("None");
        event.setOrganisation(users.findByUsername("eo1").orElseThrow().getOrganisation());
        event.setStatus(EventStatus.confirmed); event.setFirstConfirmedAt(OffsetDateTime.now().minusDays(1));
        event.setRegistrationNeeds(true); event.setRegistrationCapacity(2); event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(1));
        created.add(event.getEventId()); return events.saveAndFlush(event);
    }
}
