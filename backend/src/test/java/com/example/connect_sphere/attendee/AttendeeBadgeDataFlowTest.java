package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

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
@Tag("AT01")
@Tag("AT02")
@Tag("AT03")
@Tag("AT04")
class AttendeeBadgeDataFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("AT01 AT02 AT04 (cross-cutting): a full event exposes own confirmed status over withdrawn history and no other attendee's identity")
    void confirmedPriorityAndOwnership() throws Exception {
        Event event = event(); FlowSupport flow = new FlowSupport(mvc, users);
        registration(event, flow.idOf("att1"), "confirmed");
        registration(event, flow.idOf("att1"), "withdrawn");
        String target = "$[?(@.eventId == '" + event.getEventId() + "')]";
        mvc.perform(get("/api/attendee/events").with(flow.as("att1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(target + ".registrationStatus").value(org.hamcrest.Matchers.contains("confirmed")))
                .andExpect(jsonPath(target + ".availablePlaces").value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath(target + ".registrationOpensAt").isNotEmpty())
                .andExpect(jsonPath(target + ".registrationClosesAt").isNotEmpty());
        mvc.perform(get("/api/attendee/events/" + event.getEventId()).with(flow.as("att2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").isEmpty())
                .andExpect(jsonPath("$.registrationId").isEmpty()).andExpect(jsonPath("$.availablePlaces").value(0));
        mvc.perform(get("/api/attendee/events/" + event.getEventId()).with(flow.as("att1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("confirmed"));
    }

    @Test
    @DisplayName("AT02 AT04 (boundary): withdrawn history remains visible on cancelled and reconfirming events without consuming capacity")
    void withdrawnHistory() throws Exception {
        Event event = event(); FlowSupport flow = new FlowSupport(mvc, users);
        registration(event, flow.idOf("att1"), "withdrawn");
        for (EventStatus lifecycle : new EventStatus[] {EventStatus.pending, EventStatus.cancelled}) {
            event.setStatus(lifecycle); events.saveAndFlush(event);
            mvc.perform(get("/api/attendee/events/" + event.getEventId()).with(flow.as("att1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.registrationStatus").value("withdrawn"))
                    .andExpect(jsonPath("$.registrationId").isEmpty()).andExpect(jsonPath("$.availablePlaces").value(1));
        }
        assertThat(jdbc.queryForObject("SELECT status FROM event_registrations WHERE event_id=?", String.class, event.getEventId())).isEqualTo("withdrawn");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, event.getEventId())).isZero();
        assertThat(event.getRegistrationCapacity()).isEqualTo(1);
    }

    private void registration(Event event, UUID owner, String state) {
        jdbc.update("INSERT INTO event_registrations(registration_id,event_id,attendee_id,attendee_name,reference,registered_at,status) VALUES (?,?,?,'Badge attendee',?,now(),?)",
                UUID.randomUUID(), event.getEventId(), owner, "BADGE-" + UUID.randomUUID(), state);
    }
    private Event event() {
        Event event = new Event(); event.setEventId(UUID.randomUUID()); event.setEventName("Attendee badge test");
        event.setPurpose("Test badge inputs"); event.setStartDatetime(OffsetDateTime.now().plusDays(2));
        event.setEndDatetime(OffsetDateTime.now().plusDays(3)); event.setExpectedAttendance(1);
        event.setVenueRequirements("None"); event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.now().minusDays(1)); event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(1); event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(1));
        return events.saveAndFlush(event);
    }
}
