package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
@Tag("AT08")
@Tag("AT04")
class AttendeeWithdrawalServiceTest {
    private final AttendeeRegistrationRepository registrations = mock(AttendeeRegistrationRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final JourneyStatusResolver statuses = new JourneyStatusResolver(events, clock);
    private final UUID owner = UUID.randomUUID();
    private final AttendeeRegistrationService service = new AttendeeRegistrationService(
            registrations, events, users, statuses, notifications, clock);
    private final AttendeeRegistrationQueryService queries = new AttendeeRegistrationQueryService(
            registrations, events, statuses, clock);

    @ParameterizedTest
    @CsvSource({"confirmed", "pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved"})
    @DisplayName("AT08 AT04 (happy): shared eligibility allows future withdrawal during re-confirmation without registration-window checks")
    void withdrawsAndPreservesHistory(String lifecycle) {
        Event event = event(lifecycle);
        // Even an opening date after now is irrelevant to voluntary withdrawal.
        event.setRegistrationOpensAt(OffsetDateTime.now(clock).plusHours(1));
        AttendeeRegistration registration = registration(event, "confirmed");
        stub(event, registration);
        assertThat(queries.get(owner, registration.getRegistrationId()).withdrawalAvailable()).isTrue();

        var result = service.withdraw(registration.getRegistrationId(), owner);

        assertThat(result.registrationStatus()).isEqualTo("withdrawn");
        assertThat(result.withdrawnAt()).isEqualTo(OffsetDateTime.now(clock));
        assertThat(result.reference()).isEqualTo("CS-ORIGINAL");
        assertThat(result.registrationId()).isEqualTo(registration.getRegistrationId());
        assertThat(result.registeredAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        assertThat(result.withdrawalAvailable()).isFalse();
        assertThat(result.withdrawalReason()).isEqualTo("This registration has already been withdrawn.");
        assertThat(registration.getAttendeeId()).isEqualTo(owner);
        assertThat(event.getStatus()).isEqualTo(EventStatus.valueOf(lifecycle));
        var order = inOrder(registrations, events);
        order.verify(registrations).findOwnedEventId(registration.getRegistrationId(), owner);
        order.verify(events).findForUpdate(event.getEventId());
        order.verify(registrations).findOwnedForUpdate(registration.getRegistrationId(), owner);
        order.verify(registrations).saveAndFlush(registration);
        verify(events, never()).save(any());
        verifyNoInteractions(users);
        verify(notifications).createAttendeeWithdrawalConfirmation(owner, event.getEventId(), event.getEventName(),
                "CS-ORIGINAL", registration.getRegistrationId(), OffsetDateTime.now(clock));
    }

    @ParameterizedTest
    @CsvSource({
        "withdrawn, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, This registration has already been withdrawn.",
        "event_cancelled, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, Withdrawal is unavailable because the event has been cancelled.",
        "confirmed, cancelled, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, Withdrawal is unavailable because the event has been cancelled.",
        "confirmed, completed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, Withdrawal is unavailable because the event has completed.",
        "confirmed, confirmed, 2026-12-30T00:00:00Z, 2026-12-31T00:00:00Z, Withdrawal is unavailable because the event has completed.",
        "confirmed, confirmed, 2027-01-01T00:00:00Z, 2027-01-02T00:00:00Z, Withdrawal is unavailable because the event has started.",
        "confirmed, pending, 2026-12-31T00:00:00Z, 2027-01-02T00:00:00Z, Withdrawal is unavailable because the event has started.",
        "confirmed, confirmed, , 2027-01-03T00:00:00Z, Withdrawal availability cannot be determined without an event start time.",
        "inactive, confirmed, 2027-01-02T00:00:00Z, 2027-01-03T00:00:00Z, This registration is not active."
    })
    @DisplayName("AT08 AT04 (boundary): read eligibility and command refuse the same states without changing history")
    void rejectsIneligible(String stored, String lifecycle, String start, String end, String reason) {
        Event event = event(lifecycle);
        event.setStartDatetime(start == null ? null : OffsetDateTime.parse(start));
        event.setEndDatetime(OffsetDateTime.parse(end));
        AttendeeRegistration registration = registration(event, stored);
        OffsetDateTime previous = "withdrawn".equals(stored) ? OffsetDateTime.now(clock).minusDays(1) : null;
        registration.setWithdrawnAt(previous);
        stub(event, registration);

        var details = queries.get(owner, registration.getRegistrationId());
        assertThat(details.withdrawalAvailable()).isFalse();
        assertThat(details.withdrawalReason()).isEqualTo(reason);
        assertThatThrownBy(() -> service.withdraw(registration.getRegistrationId(), owner))
                .isInstanceOfSatisfying(AttendeeRegistrationException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo(reason);
                });
        assertThat(registration.getStatus()).isEqualTo(stored);
        assertThat(registration.getWithdrawnAt()).isEqualTo(previous);
        verify(registrations, never()).saveAndFlush(any());
        verifyNoInteractions(users, notifications);
    }

    @Test
    @DisplayName("AT08 (negative): a missing or other-owned registration is unavailable before reading any event")
    void rejectsUnknownOwner() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.withdraw(id, owner))
                .isInstanceOfSatisfying(AttendeeRegistrationException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(404);
                    assertThat(ex.getMessage()).isEqualTo("Registration unavailable.");
                });
        verify(registrations).findOwnedEventId(id, owner);
        verifyNoMoreInteractions(registrations);
        verifyNoInteractions(events, users, notifications);
    }

    @Test
    @DisplayName("AT08 (boundary): a request waiting for locks checks the time after both locks are acquired")
    void timeIsCheckedAfterLock() {
        Event event = event("confirmed");
        AttendeeRegistration registration = registration(event, "confirmed");
        stub(event, registration);
        Clock changing = mock(Clock.class);
        when(changing.getZone()).thenReturn(ZoneOffset.UTC);
        when(changing.instant()).thenReturn(clock.instant());
        when(registrations.findOwnedForUpdate(registration.getRegistrationId(), owner)).thenAnswer(invocation -> {
            when(changing.instant()).thenReturn(event.getStartDatetime().toInstant());
            return Optional.of(registration);
        });
        var waitingService = new AttendeeRegistrationService(registrations, events, users, statuses, notifications, changing);
        assertThatThrownBy(() -> waitingService.withdraw(registration.getRegistrationId(), owner))
                .hasMessage("Withdrawal is unavailable because the event has started.");
        assertThat(registration.getStatus()).isEqualTo("confirmed");
        assertThat(registration.getWithdrawnAt()).isNull();
        verify(registrations, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "registration"})
    @DisplayName("AT08 (conflict): a missing record after lock acquisition remains unavailable without saving")
    void missingDuringLocking(String missing) {
        Event event = event("confirmed");
        AttendeeRegistration registration = registration(event, "confirmed");
        stub(event, registration);
        if (missing.equals("event")) {
            when(events.findForUpdate(event.getEventId())).thenReturn(Optional.empty());
        } else {
            when(registrations.findOwnedForUpdate(registration.getRegistrationId(), owner)).thenReturn(Optional.empty());
        }
        assertThatThrownBy(() -> service.withdraw(registration.getRegistrationId(), owner))
                .isInstanceOfSatisfying(AttendeeRegistrationException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(404);
                    assertThat(ex.getMessage()).isEqualTo("Registration unavailable.");
                });
        assertThat(registration.getStatus()).isEqualTo("confirmed");
        assertThat(registration.getWithdrawnAt()).isNull();
        verify(registrations, never()).saveAndFlush(any());
        verifyNoInteractions(users, notifications);
    }

    @Test
    @DisplayName("AT08 AT04 (boundary): legacy withdrawn dates remain unknown in registration history")
    void legacyTimestampIsNotInvented() {
        Event event = event("confirmed");
        AttendeeRegistration registration = registration(event, "withdrawn");
        stub(event, registration);
        assertThat(queries.get(owner, registration.getRegistrationId()).withdrawnAt()).isNull();
        verify(registrations, never()).saveAndFlush(any());
    }

    private void stub(Event event, AttendeeRegistration registration) {
        UUID id = registration.getRegistrationId();
        when(registrations.findOwnedEventId(id, owner)).thenReturn(Optional.of(event.getEventId()));
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(registrations.findOwnedForUpdate(id, owner)).thenReturn(Optional.of(registration));
        when(registrations.findByRegistrationIdAndAttendeeId(id, owner)).thenReturn(Optional.of(registration));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
    }

    private Event event(String status) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("AT08 event");
        event.setStatus(EventStatus.valueOf(status));
        event.setStartDatetime(OffsetDateTime.parse("2027-01-02T00:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2027-01-03T00:00:00Z"));
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        return event;
    }

    private AttendeeRegistration registration(Event event, String status) {
        AttendeeRegistration registration = new AttendeeRegistration();
        registration.setRegistrationId(UUID.randomUUID());
        registration.setEventId(event.getEventId());
        registration.setAttendeeId(owner);
        registration.setRegistrationReference("CS-ORIGINAL");
        registration.setRegisteredAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        registration.setStatus(status);
        return registration;
    }
}
