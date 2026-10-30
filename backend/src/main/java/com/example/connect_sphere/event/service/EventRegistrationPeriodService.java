package com.example.connect_sphere.event.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.dto.RegistrationPeriodRequest;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;

/** EO12: the owning organisation's audited registration-period update. */
@Service
public class EventRegistrationPeriodService {
    private final EventRepository events;
    private final EventRequestRepository eventRequests;
    private final ActivityService activity;
    private final EventRegistrationService registrationInfo;
    private final Clock clock;

    @Autowired
    public EventRegistrationPeriodService(EventRepository events, EventRequestRepository eventRequests,
            ActivityService activity, EventRegistrationService registrationInfo) {
        this(events, eventRequests, activity, registrationInfo, Clock.systemUTC());
    }

    EventRegistrationPeriodService(EventRepository events, EventRequestRepository eventRequests,
            ActivityService activity, EventRegistrationService registrationInfo, Clock clock) {
        this.events = events;
        this.eventRequests = eventRequests;
        this.activity = activity;
        this.registrationInfo = registrationInfo;
        this.clock = clock;
    }

    @Transactional
    public RegistrationInfoDto update(String organisation, UUID organiserId, UUID eventId,
            RegistrationPeriodRequest request) {
        Event event = events.findForUpdate(eventId)
                .filter(value -> organisation != null && organisation.equals(value.getOrganisation()))
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (!Boolean.TRUE.equals(event.getRegistrationNeeds())) {
            throw new RegistrationPeriodException(HttpStatus.CONFLICT,
                    "Registration is not required for this event, so its registration period cannot be configured.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (event.getStatus() == EventStatus.cancelled || event.getStatus() == EventStatus.completed
                || (event.getEndDatetime() != null && !now.isBefore(event.getEndDatetime()))) {
            throw new RegistrationPeriodException(HttpStatus.CONFLICT,
                    "The registration period cannot be changed for a cancelled or ended event.");
        }
        if (request == null) {
            throw invalid("Provide both a registration opening and closing date and time.");
        }

        OffsetDateTime opensAt = parse(request.opensAt(), "opening");
        OffsetDateTime closesAt = parse(request.closesAt(), "closing");
        if (event.getStartDatetime() == null) {
            throw invalid("The event start date and time must be set before configuring registration.");
        }
        if (!closesAt.isAfter(opensAt)) {
            throw invalid("The registration closing date and time must be after the opening date and time.");
        }
        if (closesAt.isAfter(event.getStartDatetime())) {
            throw invalid("Registration must close no later than the event start date and time.");
        }

        OffsetDateTime previousOpensAt = event.getRegistrationOpensAt();
        boolean openingHasPassed = previousOpensAt != null && !now.isBefore(previousOpensAt);
        if (openingHasPassed && !opensAt.isEqual(previousOpensAt)) {
            throw new RegistrationPeriodException(HttpStatus.CONFLICT,
                    "Registration has already opened; only the closing date and time can be changed.");
        }
        if (!openingHasPassed && opensAt.isBefore(now)) {
            throw invalid("The registration opening date and time cannot be in the past.");
        }

        var origin = eventRequests.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("The event's originating request was not found."));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("registrationOpensAt", opensAt.toString());
        values.put("registrationClosesAt", closesAt.toString());

        event.setRegistrationOpensAt(opensAt);
        event.setRegistrationClosesAt(closesAt);
        events.save(event);
        activity.record(new ActivityService.Entry(origin.getRequestId(), eventId,
                ActivityType.registration_period_updated, organiserId, "Registration period updated.",
                null, null, null, null, values));

        return registrationInfo.forOrganiser(organisation, eventId);
    }

    private static OffsetDateTime parse(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid("Enter both a valid registration " + label + " date and time.");
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid("Enter a valid registration " + label + " date and time, including the time zone.");
        }
    }

    private static RegistrationPeriodException invalid(String message) {
        return new RegistrationPeriodException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
