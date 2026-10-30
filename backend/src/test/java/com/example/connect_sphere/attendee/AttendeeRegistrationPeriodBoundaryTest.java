package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
@Tag("EO12")
class AttendeeRegistrationPeriodBoundaryTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ATTENDEE_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final AttendeeRegistrationRepository registrations = mock(AttendeeRegistrationRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AttendeeRegistrationService service = new AttendeeRegistrationService(registrations, events, users,
            new JourneyStatusResolver(events, CLOCK), notifications, CLOCK);

    @Test
    @DisplayName("EO12 AC7 (boundary): registration is accepted exactly at the configured opening time")
    void acceptsAtConfiguredOpening() {
        Event event = event(NOW, NOW.plusDays(1));
        prepare(event);
        when(registrations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.register(EVENT_ID, ATTENDEE_ID).status()).isEqualTo("confirmed");
        verify(registrations).saveAndFlush(any());
        verify(notifications).createAttendeeRegistrationConfirmation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EO12 AC7, AC9 (boundary): registration is refused exactly at the configured closing time")
    void refusesAtConfiguredCloseWithoutCreatingRegistration() {
        Event event = event(NOW.minusDays(1), NOW);
        prepare(event);

        assertThatThrownBy(() -> service.register(EVENT_ID, ATTENDEE_ID))
                .isInstanceOf(AttendeeRegistrationException.class)
                .hasMessage("Registration is closed.");
        verify(registrations, never()).saveAndFlush(any());
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any(), any());
    }

    private void prepare(Event event) {
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        User attendee = new User();
        attendee.setUserId(ATTENDEE_ID);
        attendee.setUsername("attendee");
        attendee.setEmail("attendee@example.com");
        attendee.setRole(UserRole.attendee);
        when(users.findById(ATTENDEE_ID)).thenReturn(Optional.of(attendee));
        when(registrations.findByEventIdAndAttendeeIdAndStatus(EVENT_ID, ATTENDEE_ID, "confirmed"))
                .thenReturn(Optional.empty());
        when(registrations.countByEventIdAndStatus(EVENT_ID, "confirmed")).thenReturn(0L);
    }

    private static Event event(OffsetDateTime opensAt, OffsetDateTime closesAt) {
        Event event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Fair");
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(NOW.minusDays(2));
        event.setStartDatetime(NOW.plusDays(2));
        event.setEndDatetime(NOW.plusDays(3));
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(5);
        event.setRegistrationOpensAt(opensAt);
        event.setRegistrationClosesAt(closesAt);
        return event;
    }
}
