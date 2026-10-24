package com.example.connect_sphere.changerequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO03/EC-NEW3 notifications are sent after the change request commits, so
 * this test really commits (like NotificationFlowTest) and removes what it
 * created afterwards. Each notification reaches the right person, says what
 * happened, and opens the request's change requests.
 */
@Tag("integration")
@Tag("EO03")
@Tag("EC-NEW3")
@SpringBootTest
@AutoConfigureMockMvc
class ChangeRequestNotificationFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private FlowSupport flow;
    private String eventName;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        eventName = "EO03 Notice " + UUID.randomUUID();
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE event_request_activity DISABLE TRIGGER trg_activity_append_only");
            jdbc.update("DELETE FROM notifications WHERE event_name = ?", eventName);
            jdbc.update("DELETE FROM event_change_requests WHERE request_id IN "
                    + "(SELECT request_id FROM event_requests WHERE event_name = ?)", eventName);
            jdbc.update("DELETE FROM event_request_activity WHERE request_id IN "
                    + "(SELECT request_id FROM event_requests WHERE event_name = ?)", eventName);
            jdbc.update("DELETE FROM event_requests WHERE event_name = ?", eventName);
            jdbc.execute("ALTER TABLE event_request_activity ENABLE TRIGGER trg_activity_append_only");
        });
    }

    private UUID ask(UUID requestId, int attendance) throws Exception {
        String body = mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"reason\":\"More guests\",\"details\":"
                                + FlowSupport.requestBody(eventName, attendance, "none") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(flow.read(body).get("changeRequestId").asString());
    }

    private String mine(String type) {
        return "$[?(@.type == '" + type + "' && @.eventName == '" + eventName + "')]";
    }

    @Test
    @DisplayName("EO03 AC31, AC38, EC-NEW3 AC39, AC41 (happy): the coordinator is told of the request and of its withdrawal; the organiser of each decision with the reason; every link opens the request's change requests")
    void notificationsReachTheRightPeople() throws Exception {
        UUID requestId = flow.submittedAndAssigned("eo1", "ec1", FlowSupport.requestBody(eventName, 150, "none"));
        String link = "/change-requests/" + requestId;

        UUID first = ask(requestId, 200);
        mvc.perform(get("/api/notifications").with(flow.as("ec1")))
                .andExpect(jsonPath(mine("change_request_submitted") + ".message",
                        Matchers.contains("Change requested: Expected attendance: 150 → 200. Reason: More guests")))
                .andExpect(jsonPath(mine("change_request_submitted") + ".linkPath", Matchers.contains(link)));

        mvc.perform(post("/api/change-requests/" + first + "/withdraw").with(flow.as("eo1"))).andExpect(status().isOk());
        mvc.perform(get("/api/notifications").with(flow.as("ec1")))
                .andExpect(jsonPath(mine("change_request_withdrawn") + ".linkPath", Matchers.contains(link)));

        UUID second = ask(requestId, 220);
        mvc.perform(post("/api/change-requests/" + second + "/reject").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No hall holds 220\"}"))
                .andExpect(status().isOk());
        UUID third = ask(requestId, 180);
        mvc.perform(post("/api/change-requests/" + third + "/approve").with(flow.as("ec1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[]}")).andExpect(status().isOk());

        mvc.perform(get("/api/notifications").with(flow.as("eo1")))
                .andExpect(jsonPath(mine("change_request_decided") + ".message", Matchers.containsInAnyOrder(
                        "Your change request was rejected: No hall holds 220",
                        "Your change request was approved: Expected attendance: 150 → 180.")))
                .andExpect(jsonPath(mine("change_request_decided") + ".linkPath", Matchers.everyItem(Matchers.is(link))));
        // Nobody is told about their own action.
        mvc.perform(get("/api/notifications").with(flow.as("eo1")))
                .andExpect(jsonPath(mine("change_request_submitted")).isEmpty());
    }

    @Test
    @DisplayName("EO03 AC32 (happy): with no coordinator assigned yet, the Event Coordinator Lead is told instead")
    void theLeadIsToldWhileUnassigned() throws Exception {
        UUID requestId = flow.submitted("eo1", FlowSupport.requestBody(eventName, 150, "none"));

        ask(requestId, 200);

        mvc.perform(get("/api/notifications").with(flow.as("ecl1")))
                .andExpect(jsonPath(mine("change_request_submitted")).isNotEmpty());
        mvc.perform(get("/api/notifications").with(flow.as("ec1")))
                .andExpect(jsonPath(mine("change_request_submitted")).isEmpty());
    }
}
