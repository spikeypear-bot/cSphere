package com.example.connect_sphere.venue.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.repository.VenueRepository;

import jakarta.persistence.EntityManager;

/** HTTP-to-PostgreSQL coverage; each test rolls back its catalogue records. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VenueControllerTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager entityManager;
    @Autowired VenueRepository repository;

    @Test
    void createsThenListsAndReadsSavedVenue() throws Exception {
        var response = mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"  API Test Venue  ","venueCapacity":50,
                 "supportedLayouts":["theatre","classroom"],"operatingInformation":"Mon-Fri 9-5"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueAddress").value("API Test Venue"))
                .andExpect(jsonPath("$.venueCapacity").value(50))
                .andReturn().getResponse();
        String location = response.getHeader("Location");
        assertThat(location).startsWith("/api/venues/");
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(jsonPath("$.supportedLayouts[0]").value("theatre"))
                .andExpect(jsonPath("$.supportedLayouts[1]").value("classroom"));
        mvc.perform(get("/api/venues")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venueAddress == 'API Test Venue')]").isNotEmpty());
    }

    @Test
    void unknownVenueReturns404() throws Exception {
        mvc.perform(get("/api/venues/" + UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    void malformedIdReturns400() throws Exception {
        mvc.perform(get("/api/venues/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"supportedLayouts\":[\"unknown\"]}",
            "{\"venueCapacity\":\"many\"}"})
    void malformedInputReturns400WithoutSaving(String body) throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void missingFieldsReturn422WithoutSaving() throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("venueCapacity")));
        assertThat(repository.count()).isEqualTo(before);
    }
}
