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

    public AttendeeEventDetailsService(EventRepository events, VenueBookingRecordRepository bookings,
            VenueRepository venues, AvailabilityService availability, JourneyStatusResolver statuses) {
        this.events = events;
        this.bookings = bookings;
        this.venues = venues;
        this.availability = availability;
        this.statuses = statuses;
    }

    @PreAuthorize("hasRole('ATTENDEE')")
    @Transactional(readOnly = true)
    public AttendeeEventDetailsDto get(UUID eventId) {
        Event event = events.findById(eventId)
                .filter(e -> e.getFirstConfirmedAt() != null)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        JourneyStatus journey = statuses.journeyOf(event);
        String view = journey == JourneyStatus.CONFIRMED ? "confirmed"
                : journey == JourneyStatus.COMPLETED ? "completed"
                : journey == JourneyStatus.CANCELLED ? "cancelled" : "reconfirming";
        List<AttendeeEventDetailsDto.AttendeeVenue> attendeeVenues = List.of();
        if (!view.equals("cancelled")) {
            attendeeVenues = bookings.findForEvent(eventId).stream()
                    .filter(b -> b.getStatus() == VenueBookingStatus.approved)
                    .map(this::toVenue)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        return new AttendeeEventDetailsDto(event.getEventId(), event.getEventName(), event.getDescription(),
                event.getStartDatetime(), event.getEndDatetime(), view, event.getConfirmedAt(),
                event.getAccessibilityNeeds() == null ? List.of() : List.copyOf(event.getAccessibilityNeeds()),
                event.getRegistrationNeeds(), null, null, null, null, attendeeVenues);
    }

    private AttendeeEventDetailsDto.AttendeeVenue toVenue(VenueBookingRecord booking) {
        return venues.findById(booking.getVenueId())
                .map(venue -> new AttendeeEventDetailsDto.AttendeeVenue(venue.getVenueId(),
                        venue.getVenueAddress(), availability.affected(booking.getBookingId())))
                .orElse(null);
    }
}
