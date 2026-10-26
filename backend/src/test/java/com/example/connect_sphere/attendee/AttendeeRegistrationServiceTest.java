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
@Tag("AT01")
class AttendeeRegistrationServiceTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2027-01-01T12:00:00Z");
    private final AttendeeRegistrationRepository registrations = mock(AttendeeRegistrationRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AttendeeRegistrationService service = new AttendeeRegistrationService(registrations, events, users,
            new JourneyStatusResolver(events, Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC)),
            notifications, Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("AT01 AC5 (happy): creates a confirmed registration with its own reference and timestamp")
    void createsConfirmedRegistration() {
        prepare(event());
        when(registrations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.register(EVENT_ID, USER_ID);
        assertThat(result.status()).isEqualTo("confirmed");
        assertThat(result.reference()).startsWith("CS-");
        assertThat(result.registeredAt()).isEqualTo(NOW);
        verify(registrations).saveAndFlush(any());
        verify(notifications).createAttendeeRegistrationConfirmation(USER_ID, EVENT_ID, "Workshop", result.reference());
    }

    @Test
    @DisplayName("AT01 AC6 (happy): repeated submission returns the existing active registration")
    void returnsExistingRegistration() {
        Event event = event();
        prepare(event);
        AttendeeRegistration existing = new AttendeeRegistration();
        existing.setRegistrationId(UUID.randomUUID());
        existing.setRegistrationReference("CS-EXISTING");
        existing.setEventId(EVENT_ID);
        existing.setAttendeeId(USER_ID);
        existing.setStatus("confirmed");
        existing.setRegisteredAt(NOW.minusDays(1));
        when(registrations.findByEventIdAndAttendeeIdAndStatus(EVENT_ID, USER_ID, "confirmed"))
                .thenReturn(Optional.of(existing));
        var result = service.register(EVENT_ID, USER_ID);
        assertThat(result.reference()).isEqualTo("CS-EXISTING");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 AC1 (negative): refuses a user account that is not an attendee")
    void refusesWrongRole() {
        prepare(event());
        User organiser = user();
        organiser.setRole(UserRole.eo);
        when(users.findById(USER_ID)).thenReturn(Optional.of(organiser));
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class)
                .hasMessageContaining("signed-in attendee");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 AC8 (boundary): a full event refuses registration without reserving a place")
    void refusesFullEvent() {
        prepare(event());
        when(registrations.countByEventIdAndStatus(EVENT_ID, "confirmed")).thenReturn(1L);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessage("This event is full.");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 AC8 (boundary): registration before opening time is refused without a reservation")
    void refusesBeforeOpening() {
        Event event = event();
        event.setRegistrationOpensAt(NOW.plusMinutes(1));
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessage("Registration has not opened yet.");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 AC8 (negative): an event being re-confirmed pauses new registrations")
    void refusesWhileAwaitingReconfirmation() {
        Event event = event();
        event.setStatus(EventStatus.safety_approved);
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessageContaining("awaiting re-confirmation");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 AC8 (negative): a completed event reports registration closed")
    void refusesCompletedEventAsClosed() {
        Event event = event();
        event.setStatus(EventStatus.completed);
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessage("Registration is closed.");
        verify(registrations, never()).saveAndFlush(any());
    }

    private void prepare(Event event) {
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(registrations.findByEventIdAndAttendeeIdAndStatus(EVENT_ID, USER_ID, "confirmed"))
                .thenReturn(Optional.empty());
        when(registrations.countByEventIdAndStatus(EVENT_ID, "confirmed")).thenReturn(0L);
    }

    @Test
    @DisplayName("AT01 (boundary): registration closes exactly when the event starts")
    void refusesAtEventStart() {
        Event event = event();
        event.setStartDatetime(NOW);
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessage("Registration is closed.");
        verify(registrations, never()).saveAndFlush(any());
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AT01 (boundary): registration is accepted at its opening instant")
    void acceptsAtOpening() {
        Event event = event();
        event.setRegistrationOpensAt(NOW);
        prepare(event);
        when(registrations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.register(EVENT_ID, USER_ID).status()).isEqualTo("confirmed");
        verify(registrations).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 (negative): an opening at event start is invalid and saves nothing")
    void refusesInvalidWindow() {
        Event event = event();
        event.setRegistrationOpensAt(event.getStartDatetime());
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOf(AttendeeRegistrationException.class).hasMessageContaining("settings are incomplete or invalid");
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AT01 (cross-cutting): a repeated registration for a cancelled event uses EO10's event_cancelled status")
    void existingRegistrationReflectsCancellation() {
        Event event = event();
        event.setStatus(EventStatus.cancelled);
        prepare(event);
        AttendeeRegistration existing = new AttendeeRegistration();
        existing.setStatus("confirmed");
        existing.setRegistrationReference("CS-CANCELLED");
        when(registrations.findByEventIdAndAttendeeIdAndStatus(EVENT_ID, USER_ID, "confirmed"))
                .thenReturn(Optional.of(existing));
        assertThat(service.register(EVENT_ID, USER_ID).status()).isEqualTo("event_cancelled");
        verify(registrations, never()).saveAndFlush(any());
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any());
    }

    private static User user() {
        User user = new User();
        user.setUserId(USER_ID);
        user.setUsername("attendee1");
        user.setEmail("attendee@example.com");
        user.setRole(UserRole.attendee);
        return user;
    }

    private static Event event() {
        Event event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Workshop");
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(NOW.minusDays(2));
        event.setStartDatetime(NOW.plusDays(1));
        event.setEndDatetime(NOW.plusDays(2));
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(1);
        event.setRegistrationOpensAt(NOW.minusDays(1));
        return event;
    }
}
