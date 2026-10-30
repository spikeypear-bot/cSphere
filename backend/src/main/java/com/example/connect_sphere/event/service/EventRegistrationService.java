package com.example.connect_sphere.event.service;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventRegistration;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.repository.EventRegistrationRepository;

/** EO10: read-only registration information for the owning organisation's event. */
@Service
public class EventRegistrationService {

    private final EventRepository events;
    private final EventRegistrationRepository registrations;
    private final Clock clock;

    @Autowired
    public EventRegistrationService(EventRepository events, EventRegistrationRepository registrations) {
        this(events, registrations, Clock.systemUTC());
    }

    EventRegistrationService(EventRepository events, EventRegistrationRepository registrations, Clock clock) {
        this.events = events;
        this.registrations = registrations;
        this.clock = clock;
    }

    /** EO11: set the capacity. Owner only; must be a positive whole number not below the confirmed count. */
    @Transactional
    public RegistrationInfoDto updateCapacity(String organisation, UUID eventId, Object requested) {
        Event event = events.findForUpdate(eventId)
                .filter(e -> organisation != null && organisation.equals(e.getOrganisation()))
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (!Boolean.TRUE.equals(event.getRegistrationNeeds())) {
            throw new EventLifecycleException("Registration is not required for this event, so capacity cannot be set.");
        }
        long capacity = parseCapacity(requested);
        if (event.getStatus() == EventStatus.cancelled) {
            throw new EventLifecycleException("This event is cancelled, so its capacity cannot be changed.");
        }
        long confirmed = registrations.findByEventIdOrderByRegisteredAtAsc(eventId).stream()
        .filter(r -> "confirmed".equals(r.getStatus())).count();
        
        if (capacity < confirmed) {
            throw new EventLifecycleValidationException("Capacity cannot be lower than the " + confirmed
                    + " active confirmed registration(s).");
        }
        event.setRegistrationCapacity((int) capacity);
        events.save(event);
        return forOrganiser(organisation, eventId);
    }

    private static long parseCapacity(Object v) {
        String msg = "Capacity must be a positive whole number.";
        if (!(v instanceof Integer || v instanceof Long)) {
            throw new EventLifecycleValidationException(msg);
        }
        long n = ((Number) v).longValue();
        if (n <= 0 || n > Integer.MAX_VALUE) {
            throw new EventLifecycleValidationException(msg);
        }
        return n;
    }

    /** Other organisations' events are reported as not found (same rule as EO04). */
    @Transactional(readOnly = true)
    public RegistrationInfoDto forOrganiser(String organisation, UUID eventId) {
        Event event = events.findById(eventId)
                .filter(e -> organisation != null && organisation.equals(e.getOrganisation()))
                .orElseThrow(() -> new EventNotFoundException(eventId));

        if (!Boolean.TRUE.equals(event.getRegistrationNeeds())) {
            return new RegistrationInfoDto(eventId, event.getEventName(), false, "not_required", null, 0, null,
                    null, null, "Registration is not required for this event.", List.of());
        }

        List<EventRegistration> all = registrations.findByEventIdOrderByRegisteredAtAsc(eventId);
        // A cancelled event releases every confirmed registration, whatever is stored.
        boolean cancelled = event.getStatus() == EventStatus.cancelled;
        java.util.function.Function<EventRegistration, String> statusOf = r ->
                cancelled && "confirmed".equals(r.getStatus()) ? "event_cancelled" : r.getStatus();
        int confirmed = (int) all.stream().filter(r -> "confirmed".equals(statusOf.apply(r))).count();
        Integer capacity = event.getRegistrationCapacity();
        Integer remaining = capacity == null ? null : Math.max(0, capacity - confirmed);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime opens = event.getRegistrationOpensAt();
        // Preserve the EO10 event-start cutoff for legacy rows without a saved close time.
        OffsetDateTime closes = event.getRegistrationClosesAt() == null
                ? event.getStartDatetime() : event.getRegistrationClosesAt();
        String state;
        if (event.getStatus() == EventStatus.cancelled || event.getStatus() == EventStatus.completed) {
            state = "closed";
        } else if (opens != null && now.isBefore(opens)) {
            state = "not_yet_open";
        } else if (closes != null && !now.isBefore(closes)) {
            state = "closed";
        } else if (remaining != null && remaining == 0) {
            state = "full";
        } else {
            state = "open";
        }

        List<RegistrationInfoDto.Attendee> attendees = all.stream()
                .map(r -> new RegistrationInfoDto.Attendee(r.getRegistrationId(), r.getAttendeeName(),
                        r.getReference(), r.getRegisteredAt(), statusOf.apply(r)))
                .toList();
        String message = all.isEmpty() ? "No registrations yet." : null;
        return new RegistrationInfoDto(eventId, event.getEventName(), true, state, capacity, confirmed, remaining,
                opens, closes, message, attendees);
    }
}
