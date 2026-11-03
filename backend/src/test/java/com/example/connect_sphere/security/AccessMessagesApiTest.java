package com.example.connect_sphere.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * AU06: the access message is not just the screen hiding a link. Moved here
 * from the e2e suite (access-messages.spec.ts), which checked it with no
 * browser: a real sign-in, then the real filter chain refuses the page's API.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("AU06")
class AccessMessagesApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("AU06 (cross-cutting): the API refuses the same person too, so the message is not just the screen hiding a link")
    void theApiRefusesTheSamePerson() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "eo1", "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("accessToken").asString();

        String refused = mvc.perform(get("/api/venue-staff/booking-requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).doesNotContain("bookingId");
    }
}
