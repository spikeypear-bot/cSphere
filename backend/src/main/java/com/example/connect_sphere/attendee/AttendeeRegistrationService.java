package com.example.connect_sphere.attendee;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

@Service
public class AttendeeRegistrationService {
    private final AttendeeRegistrationRepository registrations;
    private final EventRepository events;
    private final UserRepository users;
    private final JourneyStatusResolver statuses;
    private final NotificationService notifications;
    private final Clock clock;

    @Autowired
    public AttendeeRegistrationService(AttendeeRegistrationRepository registrations, EventRepository events,
            UserRepository users, JourneyStatusResolver statuses, NotificationService notifications) {
        this(registrations, events, users, statuses, notifications, Clock.systemUTC());
    }

    AttendeeRegistrationService(AttendeeRegistrationRepository registrations, EventRepository events,
            UserRepository users, JourneyStatusResolver statuses, NotificationService notifications, Clock clock) {
        this.registrations = registrations;
        this.events = events;
        this.users = users;
        this.statuses = statuses;
        this.notifications = notifications;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('ATTENDEE')")
    @Transactional
    public AttendeeRegistrationDto register(UUID eventId, UUID attendeeId) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        User attendee = users.findById(attendeeId)
                .filter(user -> user.getRole() == UserRole.attendee)
                .orElseThrow(() -> new AttendeeRegistrationException(403, "Only the signed-in attendee can register."));

        var existing = registrations.findByEventIdAndAttendeeIdAndStatus(eventId, attendee.getUserId(), "confirmed");
        if (existing.isPresent()) return dto(existing.get(), event);

        JourneyStatus journey = statuses.journeyOf(event);
        if (journey == JourneyStatus.CANCELLED) refuse("This event has been cancelled.");
        if (event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.completed
                || journey == JourneyStatus.COMPLETED) refuse("Registration is closed.");
        if (event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.confirmed
                && event.getFirstConfirmedAt() != null) {
            refuse("This event is awaiting re-confirmation and registration is paused.");
        }
        if (event.getStatus() != com.example.connect_sphere.event.entity.EventStatus.confirmed) {
            refuse("This event is not confirmed yet.");
        }
        if (!Boolean.TRUE.equals(event.getRegistrationNeeds())) refuse("This event does not require registration.");
        if (event.getRegistrationCapacity() == null || event.getRegistrationCapacity() < 1
                || event.getRegistrationOpensAt() == null || event.getStartDatetime() == null
                || !event.getStartDatetime().isAfter(event.getRegistrationOpensAt())) {
            throw new AttendeeRegistrationException(422, "Registration settings are incomplete or invalid. Please contact the event organiser.");
        }
        if (attendee.getUsername() == null || attendee.getUsername().isBlank()
                || attendee.getEmail() == null || !attendee.getEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new AttendeeRegistrationException(422, "Your attendee profile needs a valid name and email address before registering.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isBefore(event.getStartDatetime())) refuse("Registration is closed.");
        if (now.isBefore(event.getRegistrationOpensAt())) refuse("Registration has not opened yet.");
        if (registrations.countByEventIdAndStatus(eventId, "confirmed") >= event.getRegistrationCapacity()) {
            refuse("This event is full.");
        }

        AttendeeRegistration registration = new AttendeeRegistration();
        registration.setRegistrationId(UUID.randomUUID());
        registration.setRegistrationReference("CS-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        registration.setEventId(eventId);
        registration.setAttendeeId(attendee.getUserId());
        registration.setAttendeeName(attendee.getUsername());
        registration.setAttendeeEmail(attendee.getEmail());
        registration.setStatus("confirmed");
        registration.setRegisteredAt(now);
        AttendeeRegistration saved = registrations.saveAndFlush(registration);
        // Persist the delivery intent atomically with the registration. Only delivery waits for commit.
        notifications.createAttendeeRegistrationConfirmation(attendee.getUserId(), eventId, event.getEventName(),
                saved.getRegistrationReference(), saved.getRegistrationId());
        return dto(saved, event);
    }

    @Transactional
    public AttendeeRegistrationDetailsDto withdraw(UUID registrationId, UUID attendeeId) {
        UUID eventId = registrations.findOwnedEventId(registrationId, attendeeId)
                .orElseThrow(() -> new AttendeeRegistrationException(404, "Registration unavailable."));
        // Match AT01/lifecycle lock order: event first, then the fresh owned registration.
        Event event = events.findForUpdate(eventId)
                .orElseThrow(() -> new AttendeeRegistrationException(404, "Registration unavailable."));
        AttendeeRegistration registration = registrations.findOwnedForUpdate(registrationId, attendeeId)
                .orElseThrow(() -> new AttendeeRegistrationException(404, "Registration unavailable."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        JourneyStatus journey = statuses.journeyOf(event);
        String reason = AttendeeWithdrawalPolicy.reason(registration, event, journey, now);
        if (reason != null) refuse(reason);

        registration.setStatus("withdrawn");
        registration.setWithdrawnAt(now);
        registrations.saveAndFlush(registration);
        notifications.createAttendeeWithdrawalConfirmation(attendeeId, eventId, event.getEventName(),
                registration.getRegistrationReference(), registrationId, now);
        return AttendeeRegistrationDetailsDto.from(registration, event, journey, now);
    }

    private static void refuse(String message) { throw new AttendeeRegistrationException(409, message); }

    private static AttendeeRegistrationDto dto(AttendeeRegistration registration, Event event) {
        String status = event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.cancelled
                && "confirmed".equals(registration.getStatus()) ? "event_cancelled" : registration.getStatus();
        return new AttendeeRegistrationDto(registration.getRegistrationId(), registration.getRegistrationReference(),
                event.getEventId(), event.getEventName(), status, registration.getRegisteredAt());
    }
}
