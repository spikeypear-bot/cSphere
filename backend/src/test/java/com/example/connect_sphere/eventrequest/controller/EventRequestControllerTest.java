package com.example.connect_sphere.eventrequest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.eventrequest.service.MissingOrganisationException;

/**
 * HTTP-level slice test: does the controller wire requests/responses/errors
 * correctly, independent of real business logic (which EventRequestServiceTest
 * already covers). The service is mocked.
 */
@WebMvcTest(EventRequestController.class)
class EventRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventRequestService service;

    @Test
    void savingADraftWithAnEmptyBodyReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.saveNewDraft(eq("Acme Conferences"), any())).thenReturn(
                new EventRequestDto(id, 'C', null, null, null, null, null, null, null, null, null,
                        List.of(), null, EventRequestStatus.draft, OffsetDateTime.now(), "Acme Conferences"));

        mockMvc.perform(post("/api/event-requests")
                        .header("X-Organisation", "Acme Conferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    void listingWithoutTheOrganisationHeaderReturns400() throws Exception {
        when(service.list(null)).thenThrow(new MissingOrganisationException());

        mockMvc.perform(get("/api/event-requests"))
                .andExpect(status().isBadRequest());
    }
}
