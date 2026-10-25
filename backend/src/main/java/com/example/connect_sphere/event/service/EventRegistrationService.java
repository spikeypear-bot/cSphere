package com.example.connect_sphere.event.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public EventRegistrationService(EventRepository events, EventRegistrationRepository registrations) {
        this.events = events;
        this.registrations = registrations;
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

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime opens = event.getRegistrationOpensAt();
        // Registration closes when the event starts.
        OffsetDateTime closes = event.getStartDatetime();
        String state;
        if (opens != null && now.isBefore(opens)) {
            state = "not_yet_open";
        } else if (closes != null && now.isAfter(closes)) {
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
