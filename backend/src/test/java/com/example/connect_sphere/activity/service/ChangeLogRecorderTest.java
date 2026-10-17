package com.example.connect_sphere.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.venueavailability.VenueBecameUnavailable;

/**
 * EC-NEW5: turning announced changes into log entries. ActivityService is a
 * mock: what matters is which entry is recorded, on which request, by whom.
 */
@Tag("unit")
@Tag("EC-NEW5")
class ChangeLogRecorderTest {

    private final ActivityService activity = mock(ActivityService.class);
    private final EventRequestRepository requests = mock(EventRequestRepository.class);
    private final ChangeLogRecorder recorder = new ChangeLogRecorder(activity, requests);
    private final UUID eventId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @AfterEach
    void clearSignedInUser() {
        SecurityContextHolder.clearContext();
    }

    private void originRequest() {
        EventRequest origin = new EventRequest();
        origin.setRequestId(requestId);
        when(requests.findByEventId(eventId)).thenReturn(Optional.of(origin));
    }

    private ActivityService.Entry recorded() {
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        return entry.getValue();
    }

    @Test
    @DisplayName("EC-NEW5 AC2, AC3, AC4, AC15 (happy): an announced change becomes one entry on the event's request, with who, what and the note")
    void anAnnouncedChangeIsRecorded() {
        originRequest();
        UUID coordinator = UUID.randomUUID();

        recorder.on(new EventChange(eventId, ActivityType.equipment_requested, coordinator, "Requested 4 items"));

        ActivityService.Entry entry = recorded();
        assertThat(entry.requestId()).isEqualTo(requestId);
        assertThat(entry.eventId()).isEqualTo(eventId);
        assertThat(entry.type()).isEqualTo(ActivityType.equipment_requested);
        assertThat(entry.actorUserId()).isEqualTo(coordinator);
        assertThat(entry.message()).isEqualTo("Requested 4 items");
    }

    @Test
    @DisplayName("EC-NEW5 AC2, AC6 (happy): when the service does not name the actor, the signed-in user (e.g. Venue Staff) is recorded")
    void theSignedInUserIsTheActorWhenNotNamed() {
        originRequest();
        UUID venueStaff = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(venueStaff.toString()).claim("role", "vs").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        recorder.on(new EventChange(eventId, ActivityType.venue_booking_approved, null, "Approved Marina Hall"));

        assertThat(recorded().actorUserId()).isEqualTo(venueStaff);
    }

    @Test
    @DisplayName("EC-NEW5 AC2 (boundary): with no one to attribute it to, nothing is recorded rather than an anonymous entry")
    void noActorMeansNoEntry() {
        originRequest();

        recorder.on(new EventChange(eventId, ActivityType.venue_booking_approved, null, "Approved"));

        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EC-NEW5 AC1 (boundary): an event created outside the request flow has no log to extend")
    void anEventWithoutAnOriginatingRequestIsSkipped() {
        when(requests.findByEventId(eventId)).thenReturn(Optional.empty());

        recorder.on(new EventChange(eventId, ActivityType.equipment_requested, UUID.randomUUID(), "x"));

        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EC-NEW5 AC11 (happy): a venue becoming unavailable adds an entry to every affected event, with the reason")
    void aVenueBecomingUnavailableLogsEachAffectedEvent() {
        originRequest();
        UUID other = UUID.randomUUID();
        EventRequest otherOrigin = new EventRequest();
        otherOrigin.setRequestId(UUID.randomUUID());
        when(requests.findByEventId(other)).thenReturn(Optional.of(otherOrigin));
        UUID venueStaff = UUID.randomUUID();

        recorder.on(new VenueBecameUnavailable(List.of(eventId, other), venueStaff,
                "A venue for this event became unavailable: flooding"));

        ArgumentCaptor<ActivityService.Entry> entries = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity, times(2)).record(entries.capture());
        assertThat(entries.getAllValues()).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(ActivityType.venue_unavailable);
            assertThat(e.actorUserId()).isEqualTo(venueStaff);
            assertThat(e.message()).contains("flooding");
        });
    }
}
