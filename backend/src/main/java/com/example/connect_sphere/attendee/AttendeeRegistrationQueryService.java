package com.example.connect_sphere.attendee;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;

/** AT04 reads only. No dependency on registration commands, capacity mutations or notifications. */
@Service
@PreAuthorize("hasRole('ATTENDEE')")
@Transactional(readOnly = true)
public class AttendeeRegistrationQueryService {
    private final AttendeeRegistrationRepository registrations;
    private final EventRepository events;
    private final JourneyStatusResolver statuses;
    private final Clock clock;

    @Autowired
    public AttendeeRegistrationQueryService(AttendeeRegistrationRepository registrations,
            EventRepository events, JourneyStatusResolver statuses) {
        this(registrations, events, statuses, Clock.systemUTC());
    }

    AttendeeRegistrationQueryService(AttendeeRegistrationRepository registrations,
            EventRepository events, JourneyStatusResolver statuses, Clock clock) {
        this.registrations = registrations;
        this.events = events;
        this.statuses = statuses;
        this.clock = clock;
    }

    public List<AttendeeRegistrationDetailsDto> list(UUID attendeeId) {
        List<AttendeeRegistration> owned = registrations
                .findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(attendeeId);
        Map<UUID, Event> byId = events.findAllById(owned.stream().map(AttendeeRegistration::getEventId).distinct().toList())
                .stream().collect(Collectors.toMap(Event::getEventId, Function.identity()));
        return owned.stream().map(registration -> dto(registration, byId.get(registration.getEventId()))).toList();
    }

    public AttendeeRegistrationDetailsDto get(UUID attendeeId, UUID registrationId) {
        AttendeeRegistration registration = registrations.findByRegistrationIdAndAttendeeId(registrationId, attendeeId)
                .orElseThrow(() -> new AttendeeRegistrationException(404, "Registration unavailable."));
        return dto(registration, events.findById(registration.getEventId()).orElseThrow(
                () -> new AttendeeRegistrationException(404, "Registration unavailable.")));
    }

    private AttendeeRegistrationDetailsDto dto(AttendeeRegistration registration, Event event) {
        if (event == null) throw new AttendeeRegistrationException(404, "Registration unavailable.");
        String eventStatus = AttendeeEventStatus.of(statuses.journeyOf(event));
        String reason = withdrawalReason(registration, event, eventStatus);
        return new AttendeeRegistrationDetailsDto(registration.getRegistrationId(), registration.getRegistrationReference(),
                registration.getRegisteredAt(), registration.getStatus(), event.getEventId(), event.getEventName(),
                event.getStartDatetime(), event.getEndDatetime(), eventStatus, event.getFirstConfirmedAt() != null,
                reason == null, reason == null ? "Withdrawal is available before the event starts." : reason);
    }

    /** AT08 eligibility only: AT04 never executes withdrawal or sends its confirmation. */
    private String withdrawalReason(AttendeeRegistration registration, Event event, String eventStatus) {
        if ("withdrawn".equals(registration.getStatus())) return "This registration has already been withdrawn.";
        if ("cancelled".equals(eventStatus) || "event_cancelled".equals(registration.getStatus()))
            return "Withdrawal is unavailable because the event has been cancelled.";
        if ("completed".equals(eventStatus)) return "Withdrawal is unavailable because the event has completed.";
        if (!"confirmed".equals(registration.getStatus())) return "This registration is not active.";
        if (event.getStartDatetime() == null) return "Withdrawal availability cannot be determined without an event start time.";
        if (!OffsetDateTime.now(clock).isBefore(event.getStartDatetime()))
            return "Withdrawal is unavailable because the event has started.";
        return null;
    }
}
