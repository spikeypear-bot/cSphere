package com.example.connect_sphere.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.notification.service.NotificationService;

@Tag("integration")
@Tag("EC-C1")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventReassignmentFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoBean NotificationService notifications;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    @Test
    void coordinatorRequestIsLeadOnlyAndApprovalMovesTheEventAndOriginatingRequest() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Reassignment Summit " + UUID.randomUUID(), 100, "none"));
        UUID requesterId = flow.idOf("ec1");
        UUID newCoordinatorId = flow.idOf("ec2");

        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I am no longer available.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.requesterUserId").value(requesterId.toString()));

        mvc.perform(get("/api/event-reassignment-requests").with(flow.as("ecl1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].reason")
                        .value(org.hamcrest.Matchers.hasItem("I am no longer available.")));
        mvc.perform(get("/api/event-reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not assigned.\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/event-reassignment-requests/"
                                + requestIdFor(eventId) + "/approve").with(flow.as("ecl1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + newCoordinatorId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.coordinatorUserId").value(newCoordinatorId.toString()));

        mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("approved"));
        mvc.perform(get("/api/events/" + eventId).with(flow.as("ec2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinatorName").value("ec2"))
                .andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(get("/api/event-requests").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventId == '" + eventId + "')].coordinatorId")
                        .value(org.hamcrest.Matchers.hasItem(newCoordinatorId.toString())));
    }

    @Test
    void pendingRequestCannotBeSubmittedTwice() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1",
                FlowSupport.requestBody("Pending Reassignment " + UUID.randomUUID(), 100, "none"));
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Need a transfer.\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Second request.\"}"))
                .andExpect(status().isConflict());
    }

    private String requestIdFor(UUID eventId) throws Exception {
        String json = mvc.perform(get("/api/events/" + eventId + "/reassignment-requests").with(flow.as("ec1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return flow.read(json).get(0).get("reassignmentRequestId").asString();
    }
}
