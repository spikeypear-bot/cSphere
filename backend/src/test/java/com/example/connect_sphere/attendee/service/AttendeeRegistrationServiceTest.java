package com.example.connect_sphere.attendee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.attendee.entity.AttendeeRegistration;
import com.example.connect_sphere.attendee.repository.AttendeeRegistrationRepository;
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
@Tag("AT03")
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
        verify(notifications).createAttendeeRegistrationConfirmation(USER_ID, EVENT_ID, "Workshop", result.reference(), result.registrationId());
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
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any(), any());
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
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any(), any());
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

    private void refused(Event event, int status, String message) {
        prepare(event);
        assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                .isInstanceOfSatisfying(AttendeeRegistrationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(status))
                .hasMessage(message);
        verify(registrations, never()).saveAndFlush(any());
        verify(notifications, never()).createAttendeeRegistrationConfirmation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AT01 (negative): a cancelled event refuses a new registration and saves nothing")
    void refusesCancelledEvent() {
        Event event = event();
        event.setStatus(EventStatus.cancelled);
        refused(event, 409, "This event has been cancelled.");
    }

    @Test
    @DisplayName("AT01 (boundary): a confirmed event that has already ended reports registration closed")
    void refusesEndedEvent() {
        Event event = event();
        event.setStartDatetime(NOW.minusHours(3));
        event.setEndDatetime(NOW.minusMinutes(1));
        refused(event, 409, "Registration is closed.");
    }

    @Test
    @DisplayName("AT01 (negative): an event never confirmed refuses registration as not confirmed yet")
    void refusesNeverConfirmedEvent() {
        Event event = event();
        event.setStatus(EventStatus.pending);
        event.setFirstConfirmedAt(null);
        refused(event, 409, "This event is not confirmed yet.");
    }

    @Test
    @DisplayName("AT01 (negative): an event that needs no registration, or has not said, refuses one")
    void refusesEventWithoutRegistration() {
        Event event = event();
        event.setRegistrationNeeds(false);
        refused(event, 409, "This event does not require registration.");
        event.setRegistrationNeeds(null);
        refused(event, 409, "This event does not require registration.");
    }

    @Test
    @DisplayName("AT01 (negative): missing capacity, missing opening time, or a close after the event starts is invalid and saves nothing")
    void refusesIncompleteSettings() {
        String invalid = "Registration settings are incomplete or invalid. Please contact the event organiser.";
        Event noCapacity = event();
        noCapacity.setRegistrationCapacity(null);
        refused(noCapacity, 422, invalid);
        Event noOpening = event();
        noOpening.setRegistrationOpensAt(null);
        refused(noOpening, 422, invalid);
        Event closesLate = event();
        closesLate.setRegistrationClosesAt(closesLate.getStartDatetime().plusMinutes(1));
        refused(closesLate, 422, invalid);
    }

    @Test
    @DisplayName("AT01 (negative): an attendee without a name or a valid email is asked to fix their profile, and nothing is saved")
    void refusesIncompleteProfile() {
        String profile = "Your attendee profile needs a valid name and email address before registering.";
        for (String[] nameAndEmail : new String[][] {{null, "a@example.com"}, {"  ", "a@example.com"},
                {"attendee1", null}, {"attendee1", "not-an-email"}}) {
            User attendee = user();
            attendee.setUsername(nameAndEmail[0]);
            attendee.setEmail(nameAndEmail[1]);
            prepare(event());
            when(users.findById(USER_ID)).thenReturn(Optional.of(attendee));
            assertThatThrownBy(() -> service.register(EVENT_ID, USER_ID))
                    .isInstanceOfSatisfying(AttendeeRegistrationException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(422))
                    .hasMessage(profile);
        }
        verify(registrations, never()).saveAndFlush(any());
    }
}
