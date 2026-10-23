package com.example.connect_sphere.eventrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/**
 * EO-C2 through the real API, Spring Security and PostgreSQL: the server
 * enforces the rules even when the page's own checks are bypassed, answers
 * 422 with one message per field, and a refusal changes nothing. Dates are
 * relative to now, so this test does not expire. Every test rolls back.
 */
@Tag("integration")
@Tag("EO-C2")
@Tag("EO02")
@Tag("EO26")
@Tag("EC02")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestSubmissionRulesFlowTest {

    private static final ZoneOffset SGT = ZoneOffset.ofHours(8);

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private static String body(OffsetDateTime start, OffsetDateTime end, String attendance) {
        return """
                {"eventName":"Rules Gala","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"%s","endDatetime":"%s","expectedAttendance":%s,
                 "venueRequirements":"Theatre seating","accessibilityNeeds":["none"],"registrationNeeds":false}
                """.formatted(start, end, attendance);
    }

    /** Next week, 10:00 Singapore time. */
    private static OffsetDateTime nextWeek() {
        return OffsetDateTime.now(SGT).plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private String statusOf(UUID requestId) {
        entityManager.flush();
        entityManager.clear();
        return jdbc.queryForObject("SELECT status::text FROM event_requests WHERE request_id = ?", String.class, requestId);
    }

    @Test
    @DisplayName("EO-C2 AC1, AC2, AC4, AC6, AC7, AC8 (negative): a draft with a past start, no duration and nobody attending saves, but sending it is refused with each problem against its field, and nothing changes")
    void theServerRefusesAnInvalidRequestEvenWhenThePageIsBypassed() throws Exception {
        OffsetDateTime lastYear = OffsetDateTime.now(SGT).minusYears(1);
        // AC7: the draft itself saves.
        UUID id = flow.draft("eo1", body(lastYear, lastYear, "0"));

        // AC8: a direct API call gets the server's own refusal.
        mvc.perform(post("/api/event-requests/" + id + "/submit").with(flow.as("eo1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.startDatetime").value("The start date & time must be in the future."))
                .andExpect(jsonPath("$.fieldErrors.endDatetime").value("The end date & time must be after the start date & time."))
                .andExpect(jsonPath("$.fieldErrors.expectedAttendance").value("Expected attendance must be at least 1."))
                .andExpect(jsonPath("$.missingFields").doesNotExist());

        assertThat(statusOf(id)).isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_request_activity WHERE request_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("EO-C2 AC1 (boundary): the same moment written in two time zones is no duration, and is refused")
    void theSameMomentInTwoOffsetsIsRefused() throws Exception {
        OffsetDateTime start = nextWeek();
        UUID id = flow.draft("eo1", body(start, start.withOffsetSameInstant(ZoneOffset.UTC), "150"));

        mvc.perform(post("/api/event-requests/" + id + "/submit").with(flow.as("eo1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.endDatetime").exists())
                .andExpect(jsonPath("$.fieldErrors.startDatetime").doesNotExist());
    }

    @Test
    @DisplayName("EO-C2 AC1 (happy): an overnight event next week is sent for review")
    void anOvernightEventIsSent() throws Exception {
        OffsetDateTime start = nextWeek().withHour(22);
        UUID id = flow.draft("eo1", body(start, start.plusHours(4), "40"));

        mvc.perform(post("/api/event-requests/" + id + "/submit").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("EO-C2 AC4, AC8 (negative): a decimal or non-numeric attendance is refused by the server and nothing is saved, rather than silently rounded")
    void aDecimalAttendanceIsRefusedNotRounded() throws Exception {
        OffsetDateTime start = nextWeek();
        Integer before = jdbc.queryForObject("SELECT count(*) FROM event_requests", Integer.class);

        for (String attendance : new String[] {"150.5", "\"many\""}) {
            mvc.perform(post("/api/event-requests").with(flow.as("eo1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(start, start.plusHours(2), attendance)))
                    .andExpect(status().isBadRequest());
        }
        UUID id = flow.draft("eo1", body(start, start.plusHours(2), "150"));
        mvc.perform(put("/api/event-requests/" + id).with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(start, start.plusHours(2), "149.9")))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_requests", Integer.class)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT expected_attendance FROM event_requests WHERE request_id = ?",
                Integer.class, id)).isEqualTo(150);
    }

    @Test
    @DisplayName("EO-C2 AC3 (conflict): a resubmission whose edits make the event end when it starts is refused, and the request stays as it was")
    void anInvalidResubmissionChangesNothing() throws Exception {
        UUID id = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Clarified Gala", 150, "none"));
        mvc.perform(post("/api/event-requests/" + id + "/clarifications").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Please confirm the times.\",\"flaggedFields\":[\"endDatetime\"]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/event-requests/" + id + "/resubmit").with(flow.as("eo1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"Fixed.\",\"details\":" + body(nextWeek(), nextWeek(), "150") + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.endDatetime").exists());

        assertThat(statusOf(id)).isEqualTo("clarification_required");
        assertThat(jdbc.queryForObject("SELECT event_name FROM event_requests WHERE request_id = ?", String.class, id))
                .isEqualTo("Clarified Gala");
    }

    @Test
    @DisplayName("EO-C2 AC3 (conflict): once a submitted request's start has passed, the coordinator sees why and cannot approve it; no event is created")
    void aRequestWhoseStartHasPassedCannotBeApproved() throws Exception {
        UUID id = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody("Missed Gala", 150, "none"));
        entityManager.flush();
        // Time passes: the event's date is now behind us.
        jdbc.update("UPDATE event_requests SET start_datetime = now() - interval '1 hour', "
                + "end_datetime = now() + interval '1 hour' WHERE request_id = ?", id);
        entityManager.clear();

        mvc.perform(get("/api/event-requests/" + id + "/review").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleValid").value(false))
                .andExpect(jsonPath("$.scheduleProblems.startDatetime").value("The start date & time must be in the future."));
        mvc.perform(post("/api/event-requests/" + id + "/approve").with(flow.as("ec1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.startDatetime").exists());

        assertThat(statusOf(id)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT event_id FROM event_requests WHERE request_id = ?", UUID.class, id)).isNull();
    }
}
