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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.repository.VenueRepository;

import jakarta.persistence.EntityManager;

/** HTTP-to-PostgreSQL coverage; each test rolls back its catalogue records. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "VS")
class VenueControllerTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager entityManager;
    @Autowired VenueRepository repository;

    @Test
    void createsThenListsAndReadsSavedVenue() throws Exception {
        var response = mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"  API Test Venue  ","venueCapacity":50,
                 "supportedLayouts":["theatre","classroom"],"operatingInformation":"Mon-Fri 9-5",
                 "venueAccessibilities":["step_free_access"],"venueFacilities":["stage","projection"]}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueAddress").value("API Test Venue"))
                .andExpect(jsonPath("$.venueCapacity").value(50))
                .andExpect(jsonPath("$.venueAccessibilities[0]").value("step_free_access"))
                .andExpect(jsonPath("$.venueFacilities[0]").value("stage"))
                .andReturn().getResponse();
        String location = response.getHeader("Location");
        assertThat(location).startsWith("/api/venues/");
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(jsonPath("$.venueCapacity").value(50))
                .andExpect(jsonPath("$.operatingInformation").value("Mon-Fri 9-5"))
                .andExpect(jsonPath("$.venueAccessibilities[0]").value("step_free_access"))
                .andExpect(jsonPath("$.venueFacilities[1]").value("projection"))
                .andExpect(jsonPath("$.supportedLayouts[0]").value("theatre"))
                .andExpect(jsonPath("$.supportedLayouts[1]").value("classroom"));
        mvc.perform(get("/api/venues")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.venueAddress == 'API Test Venue')].venueAccessibilities[0]").value(org.hamcrest.Matchers.hasItem("step_free_access")))
                .andExpect(jsonPath("$[?(@.venueAddress == 'API Test Venue')].venueFacilities[0]").value(org.hamcrest.Matchers.hasItem("stage")))
                .andExpect(jsonPath("$[?(@.venueAddress == 'API Test Venue')]").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"venueAccessibilities\":[],\"venueFacilities\":[]",
            ",\"venueAccessibilities\":null,\"venueFacilities\":null"})
    void emptySelectionsRemainCompatibleWithBasicCreation(String selections) throws Exception {
        var response = mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"Basic venue","venueCapacity":50,
                 "supportedLayouts":["theatre"],"operatingInformation":"Daily"
                """ + selections + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueAccessibilities").isEmpty())
                .andExpect(jsonPath("$.venueFacilities").isEmpty())
                .andReturn().getResponse();
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(response.getHeader("Location"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.venueAccessibilities").isEmpty())
                .andExpect(jsonPath("$.venueFacilities").isEmpty())
                .andExpect(jsonPath("$.venueCapacity").value(50))
                .andExpect(jsonPath("$.supportedLayouts[0]").value("theatre"))
                .andExpect(jsonPath("$.operatingInformation").value("Daily"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"venueAccessibilities\":[\"none\",\"elevators\"]",
            "\"venueAccessibilities\":[null]",
            "\"venueAccessibilities\":[\"elevators\",\"elevators\"]",
            "\"venueFacilities\":[null]",
            "\"venueFacilities\":[\"stage\",\"stage\"]"})
    void invalidSelectionsReturn422WithoutSaving(String selections) throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"Invalid selections","venueCapacity":50,
                 "supportedLayouts":["theatre"],"operatingInformation":"Daily",
                """ + selections + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").exists());
        assertThat(repository.count()).isEqualTo(before);
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
            "{\"venueCapacity\":\"many\"}", "{\"venueAccessibilities\":[\"unknown\"]}",
            "{\"venueFacilities\":[\"unknown\"]}", "{\"venueFacilities\":\"stage\"}",
            "{\"venueAccessibilities\":\"elevators\"}", "{\"venueAccessibilities\":[{}]}", "{\"venueFacilities\":[{}]}"})
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

    private String updateFixture() throws Exception {
        return mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"VS07 Venue","venueCapacity":100,"supportedLayouts":["classroom"],
                 "operatingInformation":"Mon-Fri 09:00-18:00","additionalInformation":"Keep this",
                 "venueAccessibilities":["step_free_access"],"venueFacilities":["projection"]}
                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
    }

    @ParameterizedTest
    @ValueSource(strings = {"venueCapacity", "supportedLayouts", "venueAccessibilities", "venueFacilities", "operatingInformation", "all", "empty"})
    void updatesOnlySubmittedFieldsAndPersists(String field) throws Exception {
        String location = updateFixture();
        boolean all = field.equals("all");
        String body = switch (field) {
            case "venueCapacity" -> "{\"venueCapacity\":150}";
            case "supportedLayouts" -> "{\"supportedLayouts\":[\"theatre\"]}";
            case "venueAccessibilities" -> "{\"venueAccessibilities\":[]}";
            case "venueFacilities" -> "{\"venueFacilities\":[]}";
            case "operatingInformation" -> "{\"operatingInformation\":\" Daily \"}";
            case "all" -> """
                {"venueCapacity":150,"supportedLayouts":["theatre"],"venueAccessibilities":[],
                 "venueFacilities":[],"operatingInformation":" Daily "}
                """;
            default -> "{}";
        };
        var updated = mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueAddress").value("VS07 Venue"))
                .andExpect(jsonPath("$.additionalInformation").value("Keep this"))
                .andExpect(jsonPath("$.venueCapacity").value(all || field.equals("venueCapacity") ? 150 : 100))
                .andExpect(jsonPath("$.supportedLayouts[0]").value(all || field.equals("supportedLayouts") ? "theatre" : "classroom"))
                .andExpect(jsonPath("$.venueAccessibilities.length()").value(all || field.equals("venueAccessibilities") ? 0 : 1))
                .andExpect(jsonPath("$.venueFacilities.length()").value(all || field.equals("venueFacilities") ? 0 : 1))
                .andExpect(jsonPath("$.operatingInformation").value(all || field.equals("operatingInformation") ? "Daily" : "Mon-Fri 09:00-18:00"))
                .andReturn().getResponse().getContentAsString();
        entityManager.flush(); entityManager.clear();
        assertThat(mvc.perform(get(location)).andReturn().getResponse().getContentAsString()).isEqualTo(updated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"venueCapacity\":0}", "{\"supportedLayouts\":[]}",
            "{\"operatingInformation\":\" \"}", "{\"venueAccessibilities\":[\"none\",\"elevators\"]}",
            "{\"venueFacilities\":[\"stage\",\"stage\"]}"})
    void rejectsInvalidUpdateWithoutChangingVenue(String body) throws Exception {
        String location = updateFixture();
        String before = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();
        mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").exists());
        entityManager.flush(); entityManager.clear();
        assertThat(mvc.perform(get(location)).andReturn().getResponse().getContentAsString()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"venueCapacity\":null}", "{\"supportedLayouts\":null}",
            "{\"operatingInformation\":null}", "{\"venueFacilities\":[\"unknown\"]}",
            "{\"venueAccessibilities\":[\"unknown\"]}", "{\"venueCapacity\":1.5}"})
    void rejectsMalformedUpdate(String body) throws Exception {
        mvc.perform(put(updateFixture()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMissingVenueReturns404() throws Exception {
        mvc.perform(put("/api/venues/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueCapacity\":150}"))
                .andExpect(status().isNotFound());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidCharacteristicUpdates() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "0", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "-1", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "50001", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "1.5", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "2147483648", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueCapacity", "null", 400),
                org.junit.jupiter.params.provider.Arguments.of("supportedLayouts", "[]", 422),
                org.junit.jupiter.params.provider.Arguments.of("supportedLayouts", "null", 400),
                org.junit.jupiter.params.provider.Arguments.of("supportedLayouts", "[null]", 422),
                org.junit.jupiter.params.provider.Arguments.of("supportedLayouts", "[\"classroom\",\"classroom\"]", 422),
                org.junit.jupiter.params.provider.Arguments.of("supportedLayouts", "[\"unknown\"]", 400),
                org.junit.jupiter.params.provider.Arguments.of("operatingInformation", "\"   \"", 422),
                org.junit.jupiter.params.provider.Arguments.of("operatingInformation", "null", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueAccessibilities", "null", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueAccessibilities", "[null]", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueAccessibilities", "[\"elevators\",\"elevators\"]", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueAccessibilities", "[\"none\",\"elevators\"]", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueAccessibilities", "[\"unknown\"]", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueFacilities", "null", 400),
                org.junit.jupiter.params.provider.Arguments.of("venueFacilities", "[null]", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueFacilities", "[\"stage\",\"stage\"]", 422),
                org.junit.jupiter.params.provider.Arguments.of("venueFacilities", "[\"unknown\"]", 400));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidCharacteristicUpdates")
    void validationNamesFieldAndPreservesSavedValues(String field, String value, int expectedStatus) throws Exception {
        String location = updateFixture();
        String before = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();
        mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + field + "\":" + value + "}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field)));
        entityManager.flush(); entityManager.clear();
        assertThat(mvc.perform(get(location)).andReturn().getResponse().getContentAsString()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50000})
    void acceptsCapacityBoundsAndExplicitNoAccessibility(int capacity) throws Exception {
        mvc.perform(put(updateFixture()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueCapacity\":" + capacity + ",\"venueAccessibilities\":[\"none\"],\"venueFacilities\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueCapacity").value(capacity))
                .andExpect(jsonPath("$.venueAccessibilities[0]").value("none"))
                .andExpect(jsonPath("$.venueFacilities").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Use Level 2", ""})
    void updatesAndClearsAdditionalInformation(String information) throws Exception {
        String location = updateFixture();
        mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON)
                .content("{\"additionalInformation\":\"" + information + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.additionalInformation").value(information))
                .andExpect(jsonPath("$.venueCapacity").value(100));
        entityManager.flush(); entityManager.clear();
        mvc.perform(get(location)).andExpect(jsonPath("$.additionalInformation").value(information))
                .andExpect(jsonPath("$.venueAddress").value("VS07 Venue"));
    }

    /** Compare complete rows, not only counts: status/notes/FKs must also survive. */
    private Object bookingSnapshot() {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY booking_id), '[]'::jsonb)::text
                FROM venue_bookings b
                """).getSingleResult();
    }

    @Test
    void characteristicUpdatesNeverCreateDeleteOrModifyBookings() throws Exception {
        String location = updateFixture();
        UUID venueId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        String otherLocation = updateFixture();
        UUID otherVenueId = UUID.fromString(otherLocation.substring(otherLocation.lastIndexOf('/') + 1));
        UUID eventId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO events (event_id,event_name,purpose,start_datetime,end_datetime,
                    expected_attendance,venue_requirements,status)
                VALUES (:id,'VS07 booking isolation test','Test',CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + interval '1 hour',50,'Test','confirmed')
                """).setParameter("id", eventId).executeUpdate();
        for (String bookingStatus : java.util.List.of("pending", "confirmed", "changed", "rejected", "cancelled")) {
            for (UUID bookedVenue : java.util.List.of(venueId, otherVenueId)) {
                entityManager.createNativeQuery("""
                        INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes,reject_reason)
                        VALUES (:id,:venue,:event,cast(:status as venue_booking_status),:notes,:reason)
                        """).setParameter("id", UUID.randomUUID()).setParameter("venue", bookedVenue)
                        .setParameter("event", eventId).setParameter("status", bookingStatus)
                        .setParameter("notes", "Preserve notes: " + bookingStatus)
                        .setParameter("reason", "rejected".equals(bookingStatus) ? "Preserve reason" : null)
                        .executeUpdate();
            }
        }
        Object before = bookingSnapshot();
        for (String body : java.util.List.of(
                "{\"venueCapacity\":150}", "{\"supportedLayouts\":[\"theatre\"]}",
                "{\"venueAccessibilities\":[\"elevators\"]}", "{\"venueFacilities\":[\"stage\"]}",
                "{\"operatingInformation\":\"Daily\"}", "{\"additionalInformation\":\"Level 2\"}",
                "{\"venueCapacity\":200,\"venueFacilities\":[],\"venueAccessibilities\":[]}")) {
            mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            assertThat(bookingSnapshot()).isEqualTo(before);
            entityManager.clear();
        }
        mvc.perform(put(location).contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueCapacity\":0}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(bookingSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/venues", "/api/venues/00000000-0000-0000-0000-000000000001"})
    @WithAnonymousUser
    void unauthenticatedRequestReturns401InApiErrorShape(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void catalogueAndDetailReadsPreserveVenueEventAndBookingRows() throws Exception {
        String location = updateFixture();
        UUID venueId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        UUID eventId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO events (event_id,event_name,purpose,start_datetime,end_datetime,
                    expected_attendance,venue_requirements,status,venue_id)
                VALUES (:id,'VS18 read isolation','Test',CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + interval '1 hour',40,'Test','confirmed',:venue)
                """).setParameter("id", eventId).setParameter("venue", venueId).executeUpdate();
        for (String state : java.util.List.of("pending", "confirmed", "changed", "rejected", "cancelled")) {
            entityManager.createNativeQuery("""
                    INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes)
                    VALUES (:id,:venue,:event,cast(:status as venue_booking_status),'Preserve me')
                    """).setParameter("id", UUID.randomUUID()).setParameter("venue", venueId)
                    .setParameter("event", eventId).setParameter("status", state).executeUpdate();
        }
        entityManager.flush();
        entityManager.clear();
        Object venuesBefore = entityManager.createNativeQuery(
                "SELECT jsonb_agg(to_jsonb(v) ORDER BY venue_id)::text FROM venues v").getSingleResult();
        Object eventsBefore = entityManager.createNativeQuery(
                "SELECT jsonb_agg(to_jsonb(e) ORDER BY event_id)::text FROM events e").getSingleResult();
        Object bookingsBefore = bookingSnapshot();
        for (String path : java.util.List.of("/api/venues", location, location + "/bookings")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.createNativeQuery(
                "SELECT jsonb_agg(to_jsonb(v) ORDER BY venue_id)::text FROM venues v").getSingleResult())
                .isEqualTo(venuesBefore);
        assertThat(entityManager.createNativeQuery(
                "SELECT jsonb_agg(to_jsonb(e) ORDER BY event_id)::text FROM events e").getSingleResult())
                .isEqualTo(eventsBefore);
        assertThat(bookingSnapshot()).isEqualTo(bookingsBefore);
    }

    @Test
    void allVenueStaffCanReadTheSameCatalogueAndIndividualRecords() throws Exception {
        String location = updateFixture();
        for (String staff : java.util.List.of("venue-staff-one", "venue-staff-two")) {
            mvc.perform(get("/api/venues").with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(staff).roles("VS")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.venueAddress == 'VS07 Venue')]").isNotEmpty());
            mvc.perform(get(location).with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(staff).roles("VS")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.venueAddress").value("VS07 Venue"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/venues", "/api/venues/00000000-0000-0000-0000-000000000001"})
    @WithAnonymousUser
    void invalidBearerTokenCannotReadVenues(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(roles = "ATTENDEE")
    void createByDisallowedRoleReturns403WithoutSaving() throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content("""
                {"venueAddress":"Forbidden Venue","venueCapacity":50,
                 "supportedLayouts":["theatre"]}
                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
        assertThat(repository.count()).isEqualTo(before);
    }
}
