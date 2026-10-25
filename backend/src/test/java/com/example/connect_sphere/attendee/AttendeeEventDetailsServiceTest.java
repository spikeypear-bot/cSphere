package com.example.connect_sphere.attendee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

@Tag("unit")
@Tag("AT02")
class AttendeeEventDetailsServiceTest {
    private final EventRepository events = mock(EventRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final AvailabilityService availability = mock(AvailabilityService.class);
    private final UUID eventId = UUID.randomUUID();
    private final AttendeeEventDetailsService service = new AttendeeEventDetailsService(events, bookings, venues,
            availability, new JourneyStatusResolver(events,
                    Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)));

    @Test
    @DisplayName("AT02 AC1 (happy): confirmed event returns attendee-facing approved details")
    void returnsCurrentDetailsAndApprovedVenueOnly() {
        Event event = new Event();
        event.setEventId(eventId);
        event.setEventName("Community Conference");
        event.setDescription("An event description");
        event.setStartDatetime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-01T17:00:00+08:00"));
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.parse("2026-12-01T12:00:00Z"));
        event.setConfirmedAt(OffsetDateTime.parse("2026-12-02T12:00:00Z"));
        event.setAccessibilityNeeds(List.of("step_free_access"));
        event.setSafetyFeedback("internal safety note");
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        VenueBookingRecord approved = mock(VenueBookingRecord.class);
        when(approved.getStatus()).thenReturn(VenueBookingStatus.approved);
        when(bookings.findForEvent(eventId)).thenReturn(List.of(approved));
        when(approved.getVenueId()).thenReturn(UUID.randomUUID());
        when(venues.findById(any())).thenReturn(Optional.empty());

        AttendeeEventDetailsDto dto = service.get(eventId);

        assertThat(dto.status()).isEqualTo("confirmed");
        assertThat(dto.eventName()).isEqualTo("Community Conference");
        assertThat(dto.accessibilityInformation()).containsExactly("step_free_access");
        assertThat(dto.updatedAt()).isEqualTo(event.getConfirmedAt());
        assertThat(dto.toString()).doesNotContain("internal safety note");
        verify(bookings).findForEvent(eventId);
    }

    @Test
    @DisplayName("AT02 AC4 (negative): an event without a prior confirmation is unavailable")
    void hidesUnconfirmedEvent() {
        Event event = new Event();
        event.setEventId(eventId);
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.get(eventId)).isInstanceOf(EventNotFoundException.class);
    }
}
