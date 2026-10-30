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
        return AttendeeRegistrationDetailsDto.from(registration, event, statuses.journeyOf(event), OffsetDateTime.now(clock));
    }
}
