package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventRegistrationService;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("integration")
@Tag("AT01")
@Tag("AT02")
@Tag("EO10")
class AttendeeRegistrationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired EventRegistrationService organiserRegistrations;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("AT01 AT02 (cross-cutting): request approval carries registration settings, including the chosen closing time")
    void approvalCarriesRegistrationSettings() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        String request = FlowSupport.requestBody("Registration approval", 10, "none")
                .replace("\"registrationNeeds\":false", """
                        "registrationNeeds":true,"registrationCapacity":10,
                        "registrationOpensAt":"2027-01-01T00:00:00Z",
                        "registrationClosesAt":"2027-02-01T00:00:00Z",
                        "attendeeInstructions":"Bring your ticket"
                        """);
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", request);
        String approved = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationCapacity").value(10))
                .andReturn().getResponse().getContentAsString();
        var dto = flow.read(approved);
        assertThat(OffsetDateTime.parse(dto.get("registrationClosesAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2027-02-01T00:00:00Z").toInstant());
        Event event = events.findById(UUID.fromString(dto.get("eventId").asString())).orElseThrow();
        assertThat(event.getRegistrationClosesAt().toInstant())
                .isEqualTo(OffsetDateTime.parse("2027-02-01T00:00:00Z").toInstant());
        assertThat(event.getRegistrationCapacity()).isEqualTo(10);
        assertThat(event.getRegistrationOpensAt()).isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
        assertThat(event.getAttendeeInstructions()).isEqualTo("Bring your ticket");
    }

    @Test
    @DisplayName("AT01 AT02 (edge): approval closes registration at event start when no closing time was chosen")
    void approvalDefaultsClosingToEventStart() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        String request = FlowSupport.requestBody("Registration default close", 10, "none")
                .replace("\"registrationNeeds\":false", """
                        "registrationNeeds":true,"registrationCapacity":10,
                        "registrationOpensAt":"2027-01-01T00:00:00Z"
                        """);
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", request);
        String approved = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var dto = flow.read(approved);
        assertThat(OffsetDateTime.parse(dto.get("registrationClosesAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(dto.get("startDatetime").asString()).toInstant());
    }

    @Test
    @DisplayName("AT01 AT02 EO10 (happy): attendee registration is idempotent and visible to its organiser with the same reference")
    void registrationIsSharedWithOrganiser() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event(1);
        String body = mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("confirmed"))
                .andReturn().getResponse().getContentAsString();
        String reference = flow.read(body).get("reference").asString();
        mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(reference));
        mvc.perform(get("/api/attendee/events/" + event.getEventId()).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationReference").value(reference))
                .andExpect(jsonPath("$.availablePlaces").value(0));
        mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att2")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This event is full."));
        var organiser = organiserRegistrations.forOrganiser(event.getOrganisation(), event.getEventId());
        assertThat(organiser.confirmedCount()).isEqualTo(1);
        assertThat(organiser.attendees()).hasSize(1);
        assertThat(organiser.attendees().getFirst().reference()).isEqualTo(reference);
        assertThat(organiser.closesAt()).isEqualTo(event.getStartDatetime());
        assertThat(jdbc.queryForObject("SELECT attendee_id FROM event_registrations WHERE reference = ?", UUID.class, reference))
                .isEqualTo(flow.idOf("att1"));
        assertThat(jdbc.queryForObject("SELECT attendee_email FROM event_registrations WHERE reference = ?", String.class, reference))
                .isEqualTo(users.findByUsername("att1").orElseThrow().getEmail());
    }

    @Test
    @DisplayName("AT07 (edge): full refusal creates no registration or notification; a withdrawn place frees capacity")
    void fullRefusalHasNoSideEffectsAndWithdrawalFreesPlace() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event(1);
        String path = "/api/attendee/events/" + event.getEventId();
        mvc.perform(post(path + "/registrations").with(flow.as("att1"))).andExpect(status().isOk());
        mvc.perform(post(path + "/registrations").with(flow.as("att2")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This event is full."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id = ?", Integer.class, event.getEventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_id = ?", Integer.class,
                flow.idOf("att2"), event.getEventId())).isZero();
        jdbc.update("UPDATE event_registrations SET status = 'withdrawn' WHERE event_id = ?", event.getEventId());
        mvc.perform(get(path).with(flow.as("att2"))).andExpect(jsonPath("$.availablePlaces").value(1));
        mvc.perform(post(path + "/registrations").with(flow.as("att2"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AT01 EO10 (boundary): historical confirmed rows consume capacity without fabricated account identities")
    void legacyRegistrationsConsumeCapacity() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event(1);
        jdbc.update("INSERT INTO event_registrations(registration_id, event_id, attendee_name, reference, registered_at, status) VALUES (?, ?, 'Legacy', 'LEGACY-' || ?, now(), 'confirmed')",
                UUID.randomUUID(), event.getEventId(), UUID.randomUUID().toString());
        mvc.perform(post("/api/attendee/events/" + event.getEventId() + "/registrations").with(flow.as("att1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This event is full."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id = ?", Integer.class, event.getEventId())).isEqualTo(1);
    }

    private Event event(int capacity) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Registration integration");
        event.setPurpose("Test registration");
        event.setStartDatetime(OffsetDateTime.now().plusDays(2));
        event.setEndDatetime(OffsetDateTime.now().plusDays(3));
        event.setExpectedAttendance(capacity);
        event.setVenueRequirements("None");
        event.setOrganisation(users.findByUsername("eo1").orElseThrow().getOrganisation());
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.now().minusDays(1));
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(capacity);
        event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(1));
        return events.saveAndFlush(event);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("AT01 EO10 (conflict): simultaneous attendees cannot overbook the last place and only the winner is notified")
    void simultaneousRegistrationsRespectCapacity() throws Exception {
        Event event = event(1);
        UUID id = event.getEventId();
        FlowSupport flow = new FlowSupport(mvc, users);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of("att1", "att2").stream().map(user -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Registration start timed out");
                return mvc.perform(post("/api/attendee/events/" + id + "/registrations").with(flow.as(user)))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(calls.get(0).get(30, TimeUnit.SECONDS), calls.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_registrations WHERE event_id = ? AND status = 'confirmed'", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ? AND type::text = 'attendee_registration_confirmed'", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT n.recipient_user_id = r.attendee_id FROM notifications n JOIN event_registrations r ON r.event_id = n.event_id WHERE n.event_id = ?", Boolean.class, id)).isTrue();
        } finally {
            jdbc.update("DELETE FROM notifications WHERE event_id = ?", id);
            jdbc.update("DELETE FROM event_registrations WHERE event_id = ?", id);
            jdbc.update("DELETE FROM events WHERE event_id = ?", id);
        }
    }
}
