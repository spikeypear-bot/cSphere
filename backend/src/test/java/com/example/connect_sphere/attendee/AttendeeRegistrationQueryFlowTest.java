package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("integration")
@Tag("AT04")
@Tag("AT01")
@Tag("AT02")
class AttendeeRegistrationQueryFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("AT04 AT01 AT02 (cross-cutting): registration appears immediately and viewing has no capacity, notification or registration side effects")
    void registrationThenRead() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event();
        UUID eventId = event.getEventId();
        String body = mvc.perform(post("/api/attendee/events/" + eventId + "/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(flow.read(body).get("registrationId").asString());
        String reference = flow.read(body).get("reference").asString();
        var registrationsBefore = jdbc.queryForList("SELECT * FROM event_registrations WHERE event_id = ?", eventId);
        var eventBefore = jdbc.queryForObject("SELECT row_to_json(events)::text FROM events WHERE event_id = ?", String.class, eventId);
        var notificationsBefore = jdbc.queryForList("SELECT * FROM notifications WHERE event_id = ?", eventId);

        mvc.perform(get("/api/attendee/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value(reference))
                .andExpect(jsonPath("$[0].startDatetime").exists()).andExpect(jsonPath("$[0].registeredAt").exists());
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/attendee/registrations/" + id).with(flow.as("att1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(reference))
                    .andExpect(jsonPath("$.registrationStatus").value("confirmed"))
                    .andExpect(jsonPath("$.eventStatus").value("confirmed"))
                    .andExpect(jsonPath("$.withdrawalAvailable").value(true))
                    .andExpect(jsonPath("$.attendeeEmail").doesNotExist());
        }
        mvc.perform(get("/api/attendee/events/" + eventId).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationReference").value(reference))
                .andExpect(jsonPath("$.availablePlaces").value(1));
        assertThat(jdbc.queryForList("SELECT * FROM event_registrations WHERE event_id = ?", eventId)).isEqualTo(registrationsBefore);
        assertThat(jdbc.queryForObject("SELECT row_to_json(events)::text FROM events WHERE event_id = ?", String.class, eventId)).isEqualTo(eventBefore);
        assertThat(jdbc.queryForList("SELECT * FROM notifications WHERE event_id = ?", eventId)).isEqualTo(notificationsBefore);
    }

    @Test
    @DisplayName("AT04 (negative): authentication, attendee role and record ownership are enforced through direct API access")
    void accessBoundaries() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event();
        UUID id = registration(flow, event, "att1", "confirmed");
        registration(flow, event, "att2", "withdrawn");
        // Legacy rows must never be inferred to belong to an account with the same name.
        jdbc.update("INSERT INTO event_registrations(registration_id,event_id,attendee_name,reference,registered_at,status) VALUES (?,?,'att1',?,now(),'withdrawn')",
                UUID.randomUUID(), event.getEventId(), "LEGACY-" + UUID.randomUUID());
        mvc.perform(get("/api/attendee/registrations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/attendee/registrations/" + id)).andExpect(status().isUnauthorized());
        for (String user : new String[] {"eo1", "ec1", "ecl1", "vs1", "ts1", "so1"}) {
            mvc.perform(get("/api/attendee/registrations").with(flow.as(user))).andExpect(status().isForbidden());
            mvc.perform(get("/api/attendee/registrations/" + id).with(flow.as(user))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/attendee/registrations/" + id).with(flow.as("att2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/attendee/registrations/" + UUID.randomUUID()).with(flow.as("att1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/attendee/registrations").param("attendeeId", flow.idOf("att2").toString()).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registrationId").value(id.toString()));
        mvc.perform(get("/api/attendee/registrations").with(flow.as("att3")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("AT04 (happy): live event updates and withdrawn history are visible without rewriting registrations")
    void liveStatusAndHistory() throws Exception {
        FlowSupport flow = new FlowSupport(mvc, users);
        Event event = event();
        UUID active = registration(flow, event, "att1", "confirmed");
        UUID historical = registration(flow, event, "att1", "withdrawn");
        UUID cancelled = registration(flow, event, "att1", "event_cancelled");
        mvc.perform(get("/api/attendee/registrations").with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        for (EventStatus state : new EventStatus[] {EventStatus.pending, EventStatus.cancelled, EventStatus.completed}) {
            event.setStatus(state);
            event.setEventName("Latest approved event name");
            events.saveAndFlush(event);
            mvc.perform(get("/api/attendee/registrations/" + active).with(flow.as("att1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("confirmed"))
                    .andExpect(jsonPath("$.eventName").value("Latest approved event name"))
                    .andExpect(jsonPath("$.eventStatus").value(state == EventStatus.pending ? "reconfirming" : state.name()));
        }
        jdbc.update("UPDATE event_registrations SET status='withdrawn' WHERE registration_id=?", active);
        // Real subsequent HTTP requests have a fresh persistence context. This test shares one transaction.
        entityManager.clear();
        mvc.perform(get("/api/attendee/registrations/" + active).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("withdrawn"))
                .andExpect(jsonPath("$.withdrawalAvailable").value(false));
        mvc.perform(get("/api/attendee/registrations/" + historical).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("withdrawn"));
        mvc.perform(get("/api/attendee/registrations/" + cancelled).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("event_cancelled"));
    }

    private UUID registration(FlowSupport flow, Event event, String user, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_registrations(registration_id,event_id,attendee_id,attendee_name,reference,registered_at,status) VALUES (?,?,?,?,?,now(),?)",
                id, event.getEventId(), flow.idOf(user), user, "CS-" + id, state);
        return id;
    }

    private Event event() {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("AT04 integration");
        event.setPurpose("Registration status test");
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
        return events.saveAndFlush(event);
    }
}
