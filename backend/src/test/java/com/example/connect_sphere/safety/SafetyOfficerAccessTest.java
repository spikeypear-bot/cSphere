package com.example.connect_sphere.safety;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.connect_sphere.common.web.RestAccessDeniedHandler;
import com.example.connect_sphere.common.web.RestAuthenticationEntryPoint;
import com.example.connect_sphere.config.JwtConfig;
import com.example.connect_sphere.config.SecurityConfig;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;

@Tag("unit")
@WebMvcTest(SafetyOfficerController.class)
@Import({ SecurityConfig.class, JwtConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class })
class SafetyOfficerAccessTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String CONFIRMED = "/api/safety/events/confirmed";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtAuthenticationConverter converter;
    @MockitoBean private EventRepository events;
    @MockitoBean private ReadinessService readiness;
    @MockitoBean private VenueRepository venues;

    private JwtRequestPostProcessor tokenFor(String role) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("role", role))
                .authorities(token -> converter.convert(token).getAuthorities());
    }

    @Test
    void onlySafetyOfficersCanReadConfirmedEvents() throws Exception {
        when(events.findByStatusOrderByStartDatetimeAsc(EventStatus.confirmed)).thenReturn(List.of());

        mockMvc.perform(get(CONFIRMED).with(tokenFor("safety_officer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get(CONFIRMED).with(tokenFor("ec")))
                .andExpect(status().isForbidden());

        verify(events).findByStatusOrderByStartDatetimeAsc(EventStatus.confirmed);
        verifyNoInteractions(venues, readiness);
    }

    @Test
    void aCallWithoutATokenCannotReadConfirmedEvents() throws Exception {
        mockMvc.perform(get(CONFIRMED)).andExpect(status().isUnauthorized());
        verifyNoInteractions(events, venues, readiness);
    }

    @Test
    void confirmedListIncludesEventTimeAndAssignedVenue() throws Exception {
        UUID venueId = UUID.randomUUID();
        Event event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Town Hall");
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        event.setVenueId(venueId);
        event.setStatus(EventStatus.confirmed);
        Venue venue = new Venue();
        venue.setVenueId(venueId);
        venue.setVenueAddress("1 Harbour Road");
        when(events.findByStatusOrderByStartDatetimeAsc(EventStatus.confirmed)).thenReturn(List.of(event));
        when(venues.findAllById(List.of(venueId))).thenReturn(List.of(venue));

        mockMvc.perform(get(CONFIRMED).with(tokenFor("safety_officer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventName").value("Town Hall"))
                .andExpect(jsonPath("$[0].startDatetime").value("2027-03-10T09:00:00+08:00"))
                .andExpect(jsonPath("$[0].venueAddress").value("1 Harbour Road"));
    }

    @Test
    void coordinatorConfirmationIsDeniedByTheRealSecurityConfiguration() throws Exception {
        mockMvc.perform(post("/api/events/" + EVENT_ID + "/confirm").with(tokenFor("ec")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(readiness);
    }

    @Test
    void coordinatorCannotEditSafetyChecklistItems() throws Exception {
        mockMvc.perform(patch("/api/safety/events/" + EVENT_ID + "/readiness/items/venue_booking")
                        .contentType("application/json")
                        .content("{\"checked\":true,\"note\":\"ready\"}")
                        .with(tokenFor("ec")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(readiness);
    }
}
