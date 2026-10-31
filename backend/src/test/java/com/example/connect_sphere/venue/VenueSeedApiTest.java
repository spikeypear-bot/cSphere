package com.example.connect_sphere.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

@Tag("DEV16")
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "VS")
class VenueSeedApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;

    @Test
    @DisplayName("DEV16 (happy): every seeded venue is returned by the existing catalogue and detail API")
    void catalogueExposesAllSourceRoomsAndMappedCoordinates() throws Exception {
        var json = JsonMapper.builder().build();
        var source = json.readTree(Files.readString(Path.of("dev/seed/venues.json")));
        var coordinates = json.readTree(Files.readString(Path.of("dev/seed/venue_coordinates.json")));
        var response = mvc.perform(get("/api/venues")).andExpect(status().isOk()).andReturn().getResponse();
        var catalogue = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        for (var expected : source) {
            var matches = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
            catalogue.forEach(v -> {
                if (v.get("venueName").asString().equals(expected.get("venueName").asString())
                        && v.get("venueAddress").asString().equals(expected.get("venueAddress").asString())) matches.add(v);
            });
            assertThat(matches).hasSize(1);
            var actual = matches.getFirst();
            for (String field : java.util.List.of("venueName", "venueAddress", "venueCapacity", "supportedLayouts",
                    "venueAccessibilities", "venueFacilities", "setupMinutes", "turnaroundMinutes", "additionalInformation"))
                assertThat(actual.get(field)).isEqualTo(expected.get(field));
            var coordinateMatches = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
            coordinates.forEach(c -> {
                if (c.get("venueName").equals(expected.get("venueName"))
                        && c.get("venueAddress").equals(expected.get("venueAddress"))) coordinateMatches.add(c);
            });
            assertThat(coordinateMatches).hasSize(1);
            assertThat(actual.get("latitude").asDouble()).isEqualTo(coordinateMatches.getFirst().get("latitude").asDouble());
            assertThat(actual.get("longitude").asDouble()).isEqualTo(coordinateMatches.getFirst().get("longitude").asDouble());
            var detail = mvc.perform(get("/api/venues/" + actual.get("venueId").asString()))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(json.readTree(detail.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(actual);
            var actualHours = actual.get("operatingHours");
            assertThat(actualHours.size()).isEqualTo(expected.get("operatingHours").size());
            for (int i = 0; i < actualHours.size(); i++) {
                assertThat(actualHours.get(i).get("dayOfWeek")).isEqualTo(expected.get("operatingHours").get(i).get("dayOfWeek"));
                assertThat(java.time.LocalTime.parse(actualHours.get(i).get("openTime").asString()))
                        .isEqualTo(java.time.LocalTime.parse(expected.get("operatingHours").get(i).get("openTime").asString()));
                assertThat(java.time.LocalTime.parse(actualHours.get(i).get("closeTime").asString()))
                        .isEqualTo(java.time.LocalTime.parse(expected.get("operatingHours").get(i).get("closeTime").asString()));
            }
        }
    }

    @Test
    @DisplayName("DEV16 (cross-cutting): existing characteristic updates retain and expose saved coordinates")
    void coordinatesSurviveExistingCrudAndMapperReads() throws Exception {
        var id = jdbc.queryForObject("SELECT venue_id FROM venues ORDER BY venue_id LIMIT 1", java.util.UUID.class);
        jdbc.update("UPDATE venues SET latitude=1.3,longitude=103.8 WHERE venue_id=?", id);
        entities.clear();
        mvc.perform(get("/api/venues/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(1.3)).andExpect(jsonPath("$.longitude").value(103.8));
        mvc.perform(put("/api/venues/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"additionalInformation\":\"Updated note\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(1.3))
                .andExpect(jsonPath("$.longitude").value(103.8));
        entities.flush();
        entities.clear();
        mvc.perform(get("/api/venues/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(1.3)).andExpect(jsonPath("$.longitude").value(103.8))
                .andExpect(jsonPath("$.additionalInformation").value("Updated note"));
    }
}
