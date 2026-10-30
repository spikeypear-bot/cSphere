package com.example.connect_sphere.attendee;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/** AT02: current, attendee-safe event details and only approved arrangements. */
@Service
public class AttendeeEventDetailsService {
    private final EventRepository events;
    private final VenueBookingRecordRepository bookings;
    private final VenueRepository venues;
    private final AvailabilityService availability;
    private final JourneyStatusResolver statuses;
    private final AttendeeRegistrationRepository registrations;

    public AttendeeEventDetailsService(EventRepository events, VenueBookingRecordRepository bookings,
            VenueRepository venues, AvailabilityService availability, JourneyStatusResolver statuses,
            AttendeeRegistrationRepository registrations) {
        this.events = events;
        this.bookings = bookings;
        this.venues = venues;
        this.availability = availability;
        this.statuses = statuses;
        this.registrations = registrations;
    }

    @PreAuthorize("hasRole('ATTENDEE')")
    @Transactional(readOnly = true)
    public List<AttendeeEventListItemDto> list() {
        return list(null);
    }

    @PreAuthorize("hasRole('ATTENDEE')")
    @Transactional(readOnly = true)
    public List<AttendeeEventListItemDto> list(UUID attendeeId) {
        java.util.Map<UUID, AttendeeRegistration> owned = new java.util.HashMap<>();
        if (attendeeId != null) {
            // Newest history first, but an active registration always takes precedence.
            registrations.findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(attendeeId)
                    .forEach(registration -> owned.merge(registration.getEventId(), registration,
                            (previous, next) -> "confirmed".equals(previous.getStatus()) ? previous
                                    : "confirmed".equals(next.getStatus()) ? next : previous));
        }
        return events.findByFirstConfirmedAtIsNotNullOrderByStartDatetimeAsc().stream()
                .map(event -> new AttendeeEventListItemDto(event.getEventId(), event.getEventName(),
                        event.getStartDatetime(), event.getEndDatetime(), attendeeStatus(event),
                        event.getRegistrationNeeds(), event.getRegistrationOpensAt(), registrationClosesAt(event),
                        event.getRegistrationCapacity(), remainingPlaces(event),
                        owned.containsKey(event.getEventId()) ? owned.get(event.getEventId()).getStatus() : null))
                .toList();
    }

    @PreAuthorize("hasRole('ATTENDEE')")
    @Transactional(readOnly = true)
    public AttendeeEventDetailsDto get(UUID eventId) {
        return get(eventId, null);
    }

    @Transactional(readOnly = true)
    public AttendeeEventDetailsDto get(UUID eventId, UUID attendeeId) {
        Event event = events.findById(eventId)
                .filter(e -> e.getFirstConfirmedAt() != null)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        String view = attendeeStatus(event);
        List<AttendeeEventDetailsDto.AttendeeVenue> attendeeVenues = List.of();
        if (!view.equals("cancelled")) {
            attendeeVenues = bookings.findForEvent(eventId).stream()
                    .filter(b -> b.getStatus() == VenueBookingStatus.approved)
                    .map(this::toVenue)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        AttendeeRegistration myRegistration = attendeeId == null ? null : registrations
                .findByEventIdAndAttendeeIdAndStatus(eventId, attendeeId, "confirmed").orElse(null);
        AttendeeRegistration history = myRegistration != null || attendeeId == null ? myRegistration : registrations
                .findFirstByEventIdAndAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(eventId, attendeeId).orElse(null);
        return new AttendeeEventDetailsDto(event.getEventId(), event.getEventName(), event.getDescription(),
                event.getStartDatetime(), event.getEndDatetime(), view, event.getConfirmedAt(),
                event.getAccessibilityNeeds() == null ? List.of() : List.copyOf(event.getAccessibilityNeeds()),
                event.getRegistrationNeeds(), event.getRegistrationOpensAt(), registrationClosesAt(event),
                event.getRegistrationCapacity(), remainingPlaces(event),
                event.getAttendeeInstructions(), myRegistration == null ? null : myRegistration.getRegistrationReference(),
                myRegistration == null ? null : myRegistration.getRegisteredAt(), attendeeVenues,
                myRegistration == null ? null : myRegistration.getRegistrationId(),
                history == null ? null : history.getStatus());
    }

    private Integer remainingPlaces(Event event) {
        return event.getRegistrationCapacity() == null ? null : Math.max(0,
                event.getRegistrationCapacity() - (int) registrations.countByEventIdAndStatus(event.getEventId(), "confirmed"));
    }

    private static java.time.OffsetDateTime registrationClosesAt(Event event) {
        return event.getRegistrationClosesAt() == null ? event.getStartDatetime() : event.getRegistrationClosesAt();
    }

    private String attendeeStatus(Event event) {
        return AttendeeEventStatus.of(statuses.journeyOf(event));
    }

    private AttendeeEventDetailsDto.AttendeeVenue toVenue(VenueBookingRecord booking) {
        return venues.findById(booking.getVenueId())
                .map(venue -> new AttendeeEventDetailsDto.AttendeeVenue(venue.getVenueId(), venue.getVenueName(),
                        venue.getVenueAddress(), availability.affected(booking.getBookingId())))
                .orElse(null);
    }
}
