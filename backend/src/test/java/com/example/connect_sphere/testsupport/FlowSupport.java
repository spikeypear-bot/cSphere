package com.example.connect_sphere.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared set-up for the Postgres-backed flow tests: real seeded accounts
 * (DevUserSeeder) signed in as Bearer tokens through the real filter chain,
 * and a helper that walks a request through the API to any stage. Driving
 * set-up through the API rather than inserting rows means the flow tests
 * also prove the steps before the one under test still work together.
 */
public final class FlowSupport {

    private final MockMvc mvc;
    private final UserRepository users;
    private final ObjectMapper json = new ObjectMapper();

    public FlowSupport(MockMvc mvc, UserRepository users) {
        this.mvc = mvc;
        this.users = users;
    }

    public UUID idOf(String username) {
        return users.findByUsername(username).map(User::getUserId).orElseThrow();
    }

    /** A signed-in seeded account, with the claims TokenService would mint. */
    public JwtRequestPostProcessor as(String username) {
        User user = users.findByUsername(username).orElseThrow();
        return jwt().jwt(token -> token.subject(user.getUserId().toString())
                        .claim("organisation", user.getOrganisation())
                        .claim("role", user.getRole().name()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name().toUpperCase()));
    }

    public JsonNode read(String body) {
        return json.readTree(body);
    }

    /** A complete request drafted and submitted by {@code organiser}, then
     * picked up by {@code coordinator}. Returns the request id. */
    public UUID submittedAndAssigned(String organiser, String coordinator, String requestBody) throws Exception {
        String created = mvc.perform(post("/api/event-requests").with(as(organiser))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(read(created).get("requestId").asString());
        mvc.perform(post("/api/event-requests/" + id + "/submit").with(as(organiser)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/event-requests/" + id + "/assign-coordinator").with(as(coordinator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + idOf(coordinator) + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    /** As above, then approved: returns the new event's id. */
    public UUID approvedEvent(String organiser, String coordinator, String requestBody) throws Exception {
        UUID requestId = submittedAndAssigned(organiser, coordinator, requestBody);
        String approved = mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(as(coordinator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(read(approved).get("eventId").asString());
    }

    /** A complete, valid request body. Times are fixed so overlap tests can
     * place other bookings precisely around them. */
    public static String requestBody(String name, int attendance, String accessibility) {
        return """
                {"eventName":"%s","purpose":"Quarterly update","description":"All hands",
                 "startDatetime":"2027-03-10T09:00:00+08:00","endDatetime":"2027-03-10T12:00:00+08:00",
                 "expectedAttendance":%d,"venueRequirements":"Theatre seating",
                 "accessibilityNeeds":["%s"],"registrationNeeds":false}
                """.formatted(name, attendance, accessibility);
    }
}
