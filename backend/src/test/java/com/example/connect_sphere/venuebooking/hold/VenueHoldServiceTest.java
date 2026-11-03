package com.example.connect_sphere.venuebooking.hold;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.service.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;

/** The server clock is a stub, so the expiry can pass while the hold is being checked. */
@Tag("unit")
@Tag("VS-C3")
class VenueHoldServiceTest {
    private final VenueHoldRepository holds = mock(VenueHoldRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final AvailabilityService availability = mock(AvailabilityService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final VenueHoldService service = new VenueHoldService(holds, bookings, events, venues, availability,
            publisher, mock(NotificationService.class));

    @Test
    @DisplayName("VS-C3 (boundary): an expiry that passes while the hold is being checked is refused, and no hold is saved")
    void expiryPassingDuringTheChecksIsRefused() {
        OffsetDateTime now = OffsetDateTime.parse("2027-03-01T00:00:00Z"), expiry = now.plusMinutes(1);
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setStatus(EventStatus.pending);
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setEventId(event.getEventId());
        booking.setVenueId(UUID.randomUUID());
        booking.setStatus(VenueBookingStatus.pending);
        booking.setStartDatetime(now.plusDays(30));
        booking.setEndDatetime(now.plusDays(30).plusHours(2));
        when(bookings.findEventId(booking.getBookingId())).thenReturn(Optional.of(event.getEventId()));
        when(bookings.findById(booking.getBookingId())).thenReturn(Optional.of(booking));
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        when(venues.findForUpdate(booking.getVenueId())).thenReturn(Optional.of(new Venue()));
        when(holds.now()).thenReturn(now, expiry);

        assertThatThrownBy(() -> service.create(booking.getBookingId(), expiry, UUID.randomUUID()))
                .isInstanceOf(InvalidVenueBookingException.class)
                .hasMessage("Hold expiry has already passed. Choose a later expiry.");
        verify(holds, never()).insert(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }
}
