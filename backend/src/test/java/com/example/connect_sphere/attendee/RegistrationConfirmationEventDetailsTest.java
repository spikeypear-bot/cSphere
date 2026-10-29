package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

@Tag("unit")
@Tag("AT03")
@Tag("AT02")
class RegistrationConfirmationEventDetailsTest {
    @Test
    @DisplayName("AT03 AT02 (cross-cutting): confirmation reuses multiple approved venues, excludes pending venues and carries only the current attendee's registration ID")
    void approvedArrangementsOnly() {
        var events = mock(EventRepository.class);
        var bookings = mock(VenueBookingRecordRepository.class);
        var venues = mock(VenueRepository.class);
        var availability = mock(AvailabilityService.class);
        var registrations = mock(AttendeeRegistrationRepository.class);
        var resolver = new JourneyStatusResolver(events, Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
        var service = new AttendeeEventDetailsService(events, bookings, venues, availability, resolver, registrations);
        Event event = new Event(); event.setEventId(UUID.randomUUID()); event.setEventName("Approved event name");
        event.setStatus(EventStatus.confirmed); event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
        event.setStartDatetime(OffsetDateTime.parse("2027-03-01T01:00:00Z")); event.setEndDatetime(OffsetDateTime.parse("2027-03-01T09:00:00Z"));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        UUID owner = UUID.randomUUID(); AttendeeRegistration registration = new AttendeeRegistration();
        registration.setRegistrationId(UUID.randomUUID()); registration.setRegistrationReference("CS-OWN");
        when(registrations.findByEventIdAndAttendeeIdAndStatus(event.getEventId(), owner, "confirmed")).thenReturn(Optional.of(registration));
        VenueBookingRecord first = mock(VenueBookingRecord.class), second = mock(VenueBookingRecord.class), pending = mock(VenueBookingRecord.class);
        for (VenueBookingRecord booking : List.of(first, second)) {
            when(booking.getStatus()).thenReturn(VenueBookingStatus.approved);
            Venue venue = new Venue(); venue.setVenueId(UUID.randomUUID());
            venue.setVenueName(booking == first ? "Main Hall" : "Breakout Room");
            venue.setVenueAddress(booking == first ? "10 Bay Road" : "12 Bay Road");
            when(booking.getVenueId()).thenReturn(venue.getVenueId());
            when(venues.findById(venue.getVenueId())).thenReturn(Optional.of(venue));
        }
        when(pending.getStatus()).thenReturn(VenueBookingStatus.pending);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(first, pending, second));
        var result = service.get(event.getEventId(), owner);
        assertThat(result.registrationId()).isEqualTo(registration.getRegistrationId());
        assertThat(result.venues()).extracting(AttendeeEventDetailsDto.AttendeeVenue::name).containsExactly("Main Hall", "Breakout Room");
        assertThat(result.venues()).extracting(AttendeeEventDetailsDto.AttendeeVenue::address).containsExactly("10 Bay Road", "12 Bay Road");
        verify(pending, never()).getVenueId();
        assertThat(service.get(event.getEventId(), UUID.randomUUID()).registrationId()).isNull();
        event.setStatus(EventStatus.cancelled);
        assertThat(service.get(event.getEventId(), owner).venues()).isEmpty();
    }
}
