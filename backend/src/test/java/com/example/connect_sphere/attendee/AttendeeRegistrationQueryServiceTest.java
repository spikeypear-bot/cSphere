package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;

@Tag("unit")
@Tag("AT04")
class AttendeeRegistrationQueryServiceTest {
    private final AttendeeRegistrationRepository registrations = mock(AttendeeRegistrationRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final UUID owner = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();
    private final AttendeeRegistrationQueryService service = new AttendeeRegistrationQueryService(
            registrations, events, new JourneyStatusResolver(events, clock), clock);

    @ParameterizedTest(name = "{0}, {1}, start {2}: event {4}, withdrawal {5}")
    @CsvSource({
            "confirmed, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, confirmed, true",
            "confirmed, pending, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, reconfirming, true",
            "confirmed, awaiting_safety_review, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, reconfirming, true",
            "confirmed, safety_changes_requested, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, reconfirming, true",
            "confirmed, safety_approved, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, reconfirming, true",
            "confirmed, cancelled, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, cancelled, false",
            "confirmed, completed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, completed, false",
            "confirmed, confirmed, 2026-12-30T00:00:00Z, 2026-12-31T00:00:00Z, completed, false",
            "confirmed, confirmed, 2027-01-01T00:00:00Z, 2027-01-02T00:00:00Z, confirmed, false",
            "confirmed, pending, 2026-12-31T00:00:00Z, 2027-01-02T00:00:00Z, reconfirming, false",
            "withdrawn, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, confirmed, false",
            "withdrawn, cancelled, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, cancelled, false",
            "withdrawn, completed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, completed, false",
            "event_cancelled, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, confirmed, false"
    })
    @DisplayName("AT04 (boundary): registration history stays separate from lifecycle and AT08 eligibility")
    void statusesAndEligibility(String stored, String lifecycle, String start, String end, String expected,
            boolean available) {
        Event event = event();
        event.setStatus(EventStatus.valueOf(lifecycle));
        event.setStartDatetime(OffsetDateTime.parse(start));
        event.setEndDatetime(OffsetDateTime.parse(end));
        AttendeeRegistration registration = registration(event, stored);
        when(registrations.findByRegistrationIdAndAttendeeId(registrationId, owner))
                .thenReturn(Optional.of(registration));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));

        var result = service.get(owner, registrationId);
        String expectedRegistrationStatus = "confirmed".equals(stored) && "cancelled".equals(lifecycle)
                ? "event_cancelled"
                : stored;
        assertThat(result.registrationStatus()).isEqualTo(expectedRegistrationStatus);
        assertThat(result.eventStatus()).isEqualTo(expected);
        assertThat(result.withdrawalAvailable()).isEqualTo(available);
        assertThat(result.withdrawalReason()).isNotBlank();
        assertThat(result.eventDetailsAvailable()).isTrue();
        assertThat(registration.getStatus()).isEqualTo(stored);
        verify(registrations).findByRegistrationIdAndAttendeeId(registrationId, owner);
        verifyNoMoreInteractions(registrations);
        verify(events, never()).findForUpdate(any());
    }

    @Test
    @DisplayName("AT04 (happy): own list includes all history and current event information without writes")
    void listIncludesHistory() {
        Event event = event();
        when(registrations.findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(owner))
                .thenReturn(List.of(registration(event, "withdrawn"), registration(event, "confirmed")));
        when(events.findAllById(List.of(event.getEventId()))).thenReturn(List.of(event));
        var result = service.list(owner);
        assertThat(result).extracting(AttendeeRegistrationDetailsDto::registrationStatus).containsExactly("withdrawn",
                "confirmed");
        assertThat(result.getFirst().eventName()).isEqualTo("Current approved name");
        assertThat(result.getFirst().reference()).isEqualTo("CS-HISTORY");
        assertThat(result.getFirst().registeredAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        verify(registrations).findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(owner);
        verifyNoMoreInteractions(registrations);
        verify(events).findAllById(List.of(event.getEventId()));
        verifyNoMoreInteractions(events);
    }

    @Test
    @DisplayName("AT04 (negative): another attendee's or missing registration is unavailable before reading its event")
    void ownershipIsRequired() {
        assertThatThrownBy(() -> service.get(owner, registrationId))
                .isInstanceOfSatisfying(AttendeeRegistrationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(404));
        verify(registrations).findByRegistrationIdAndAttendeeId(registrationId, owner);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("AT04 (boundary): an attendee without registrations gets an empty list")
    void emptyHistory() {
        assertThat(service.list(owner)).isEmpty();
    }

    @Test
    @DisplayName("AT04 (failure): a read failure propagates without changing history")
    void readFailure() {
        when(registrations.findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(owner))
                .thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.list(owner)).hasMessage("offline");
        verify(registrations).findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(owner);
        verifyNoMoreInteractions(registrations);
        verifyNoInteractions(events);
    }

    private Event event() {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Current approved name");
        event.setStatus(EventStatus.confirmed);
        event.setStartDatetime(OffsetDateTime.parse("2027-01-02T00:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2027-01-03T00:00:00Z"));
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        return event;
    }

    private AttendeeRegistration registration(Event event, String status) {
        AttendeeRegistration registration = new AttendeeRegistration();
        registration.setRegistrationId(registrationId);
        registration.setEventId(event.getEventId());
        registration.setAttendeeId(owner);
        registration.setRegistrationReference("CS-HISTORY");
        registration.setRegisteredAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        registration.setStatus(status);
        return registration;
    }
}
