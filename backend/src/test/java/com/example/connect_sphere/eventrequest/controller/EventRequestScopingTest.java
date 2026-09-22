package com.example.connect_sphere.eventrequest.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.user.repository.UserRepository;

/**
 * AU04 data scoping, through the real filter chain and a real database.
 *
 * Regression test for a leak that was live on 2026-09-22: while the scope came
 * from an `X-Organisation` header, a valid Event Organiser token for one
 * organisation returned another organisation's draft in full by naming it in
 * that header. The role rule did not help — the caller genuinely was an EO.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventRequestScopingTest {

    // Match DevUserSeeder's own organisation strings exactly, so the seeded
    // eo1/eo3 accounts genuinely belong to these organisations.
    private static final String ACME = "Acme Pte Ltd";
    private static final String GLOBEX = "Globex Holdings";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;

    /** An Organiser of the given organisation, shaped like a real Bearer
     * request — including a real seeded account's own id as `sub`, since
     * EventRequestController reads that as the caller's user id (createdBy)
     * as of the EO09/EO19 slice, and that column is a real foreign key to
     * `users`: a fabricated UUID (or the post-processor's own default `sub`,
     * "user") would fail the insert outright, not merely be unrealistic. */
    private JwtRequestPostProcessor organiser(String organisation) {
        String username = ACME.equals(organisation) ? "eo1" : "eo3";
        String userId = userRepository.findByUsername(username).orElseThrow().getUserId().toString();
        return jwt().jwt(token -> token.subject(userId).claim("organisation", organisation))
                .authorities(new SimpleGrantedAuthority("ROLE_EO"));
    }

    @Test
    void anOrganiserCannotReadAnotherOrganisationsRequestByNamingItInAHeader() throws Exception {
        String location = mvc.perform(post("/api/event-requests").with(organiser(ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventName\":\"Acme Board Offsite\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organisation").value(ACME))
                .andReturn().getResponse().getContentAsString();
        String id = location.replaceAll("^.*\"requestId\":\"([^\"]+)\".*$", "$1");

        // The header that used to work. It is not read any more, so the scope
        // stays whatever the *token* says — Globex — and the row is not theirs.
        mvc.perform(get("/api/event-requests/" + id).with(organiser(GLOBEX))
                        .header("X-Organisation", ACME))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/event-requests/" + id).with(organiser(ACME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventName").value("Acme Board Offsite"));
    }

    @Test
    void listingOnlyReturnsTheCallersOwnOrganisation() throws Exception {
        mvc.perform(post("/api/event-requests").with(organiser(ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventName\":\"Acme Only\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/event-requests").with(organiser(GLOBEX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventName == 'Acme Only')]").isEmpty());
    }
}
