package com.example.connect_sphere.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

import jakarta.persistence.EntityManager;

/** EO11 through the real API and PostgreSQL. Every test rolls back. */
@Tag("integration")
@Tag("EO11")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventRegistrationCapacityFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    @Test
    @DisplayName("EO11 (happy): a new capacity is saved on the event and on the request it came from, so both show the same number")
    void theCapacityIsSavedOnTheEventAndItsRequest() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Capacity Gala", 10, "none")
                .replace("\"registrationNeeds\":false", """
                        "registrationNeeds":true,"registrationCapacity":10,"registrationOpensAt":"2027-01-01T00:00:00Z"
                        """));

        mvc.perform(put("/api/events/" + eventId + "/registration-capacity").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(25))
                .andExpect(jsonPath("$.placesRemaining").value(25));

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT registration_capacity FROM events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT registration_capacity FROM event_requests WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(25);
    }
}
