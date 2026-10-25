package com.example.connect_sphere.attendee;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatus;
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
        return events.findByFirstConfirmedAtIsNotNullOrderByStartDatetimeAsc().stream()
                .map(event -> new AttendeeEventListItemDto(event.getEventId(), event.getEventName(),
                        event.getStartDatetime(), event.getEndDatetime(), attendeeStatus(event),
                        event.getRegistrationNeeds()))
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
        return new AttendeeEventDetailsDto(event.getEventId(), event.getEventName(), event.getDescription(),
                event.getStartDatetime(), event.getEndDatetime(), view, event.getConfirmedAt(),
                event.getAccessibilityNeeds() == null ? List.of() : List.copyOf(event.getAccessibilityNeeds()),
                event.getRegistrationNeeds(), event.getRegistrationOpensAt(), event.getRegistrationClosesAt(),
                event.getRegistrationCapacity(), event.getRegistrationCapacity() == null ? null
                        : Math.max(0, event.getRegistrationCapacity() - (int) registrations.countByEventIdAndStatus(eventId, "confirmed")),
                event.getAttendeeInstructions(), myRegistration == null ? null : myRegistration.getRegistrationReference(),
                myRegistration == null ? null : myRegistration.getRegisteredAt(), attendeeVenues);
    }

    private String attendeeStatus(Event event) {
        JourneyStatus journey = statuses.journeyOf(event);
        return journey == JourneyStatus.CONFIRMED ? "confirmed"
                : journey == JourneyStatus.COMPLETED ? "completed"
                : journey == JourneyStatus.CANCELLED ? "cancelled" : "reconfirming";
    }

    private AttendeeEventDetailsDto.AttendeeVenue toVenue(VenueBookingRecord booking) {
        return venues.findById(booking.getVenueId())
                .map(venue -> new AttendeeEventDetailsDto.AttendeeVenue(venue.getVenueId(), venue.getVenueName(),
                        venue.getVenueAddress(), availability.affected(booking.getBookingId())))
                .orElse(null);
    }
}
