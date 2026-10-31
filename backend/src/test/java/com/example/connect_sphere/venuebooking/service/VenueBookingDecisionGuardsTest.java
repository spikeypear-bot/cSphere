package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.connect_sphere.event.entity.*;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venuebooking.entity.*;
import com.example.connect_sphere.venuebooking.repository.*;

@Tag("DEV16") @Tag("VS03") @Tag("VS04") @Tag("unit")
class VenueBookingDecisionGuardsTest {
    private final VenueBookingRepository reads=mock(VenueBookingRepository.class);
    private final VenueBookingRecordRepository records=mock(VenueBookingRecordRepository.class);
    private final EventRepository events=mock(EventRepository.class);
    private final VenueRepository venues=mock(VenueRepository.class);
    private final AvailabilityService availability=mock(AvailabilityService.class);
    private final NotificationService notifications=mock(NotificationService.class);
    private final ApplicationEventPublisher changes=mock(ApplicationEventPublisher.class);
    private final VenueBookingService service=new VenueBookingService(reads,venues,mock(VenueMapper.class),records,events,availability,mock(TimingConflictService.class));
    private final UUID id=UUID.randomUUID(), eventId=UUID.randomUUID(), venueId=UUID.randomUUID();
    private VenueBookingRecord booking;

    @BeforeEach void fixture() {
        ReflectionTestUtils.setField(service,"plan",mock(BookingPlanService.class));
        ReflectionTestUtils.setField(service,"notifications",notifications);
        service.setChangeLog(changes);
        var event=new Event(); event.setEventId(eventId); event.setStatus(EventStatus.pending);
        event.setStartDatetime(OffsetDateTime.parse("2027-04-01T02:00:00Z")); event.setEndDatetime(event.getStartDatetime().plusHours(2));
        event.setExpectedAttendance(50); event.setAccessibilityNeeds(List.of()); event.setRequiredFacilities(List.of());
        var venue=new Venue(); venue.setVenueId(venueId); venue.setVenueCapacity(100); venue.setVenueAddress("Hall");
        booking=new VenueBookingRecord(); booking.setBookingId(id); booking.setEventId(eventId); booking.setVenueId(venueId);
        booking.setStatus(VenueBookingStatus.pending); booking.setSetupDurationMinutes(0); booking.setTurnaroundDurationMinutes(0);
        when(records.findEventId(id)).thenReturn(Optional.of(eventId));
        when(events.findForUpdate(eventId)).thenReturn(Optional.of(event));
        when(records.findById(id)).thenReturn(Optional.of(booking));
        when(venues.findForUpdate(venueId)).thenReturn(Optional.of(venue)); when(venues.findById(venueId)).thenReturn(Optional.of(venue));
        when(availability.conflictsForBooking(any(),any(),any(),any(),any())).thenReturn(List.of());
        when(availability.unavailablePeriods(any(),any(),any(),any())).thenReturn(List.of());
    }
    private void noSideEffects() {
        verify(records,never()).saveAndFlush(any());
        verifyNoInteractions(notifications,changes,reads);
        assertThat(booking.getStatus()).isEqualTo(VenueBookingStatus.pending);
    }

    @Test @DisplayName("DEV16 (conflict): losing the pending approval update never announces or returns a successful decision")
    void lostApprovalUpdate() {
        when(records.approvePending(id,VenueBookingStatus.pending,VenueBookingStatus.approved)).thenReturn(0);
        assertThatThrownBy(() -> service.approve(id)).isInstanceOf(VenueBookingStateException.class).hasMessage("This booking is no longer pending. Refresh its details.");
        noSideEffects();
    }

    @Test @DisplayName("DEV16 (conflict): losing the pending rejection update never announces or returns a successful decision")
    void lostRejectionUpdate() {
        assertThatThrownBy(() -> service.reject(id,"Unavailable",null,null)).isInstanceOf(VenueBookingStateException.class).hasMessage("This booking is no longer pending. Refresh its details.");
        noSideEffects();
    }

    @Test @DisplayName("DEV16 (conflict): a pending replacement must be resolved before rejecting its original request")
    void pendingReplacementBlocksRejection() {
        when(records.existsByReplacesBookingIdAndStatus(id,VenueBookingStatus.pending)).thenReturn(true);
        assertThatThrownBy(() -> service.reject(id,"Unavailable",null,null)).isInstanceOf(VenueBookingStateException.class).hasMessageContaining("Resolve the pending replacement request");
        verify(records,never()).rejectPending(any(),any(),any(),any(),any(),any()); noSideEffects();
    }

    @ParameterizedTest @ValueSource(strings={"eventId","event","booking","venue"})
    @DisplayName("DEV16 (failure): a missing locked approval participant refuses the decision without writes or notices")
    void missingApprovalParticipant(String missing) {
        if(missing.equals("eventId")) when(records.findEventId(id)).thenReturn(Optional.empty());
        if(missing.equals("event")) when(events.findForUpdate(eventId)).thenReturn(Optional.empty());
        if(missing.equals("booking")) when(records.findById(id)).thenReturn(Optional.empty());
        if(missing.equals("venue")) when(venues.findForUpdate(venueId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(id)).isInstanceOf(missing.equals("venue") ? VenueNotFoundException.class : VenueBookingNotFoundException.class);
        verify(records,never()).approvePending(any(),any(),any()); noSideEffects();
    }

    @ParameterizedTest @ValueSource(strings={"eventId","event","booking","venue"})
    @DisplayName("DEV16 (failure): a missing locked rejection participant refuses the decision without writes or notices")
    void missingRejectionParticipant(String missing) {
        if(missing.equals("eventId")) when(records.findEventId(id)).thenReturn(Optional.empty());
        if(missing.equals("event")) when(events.findForUpdate(eventId)).thenReturn(Optional.empty());
        if(missing.equals("booking")) when(records.findById(id)).thenReturn(Optional.empty());
        if(missing.equals("venue")) when(venues.findForUpdate(venueId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reject(id,"Unavailable",null,null)).isInstanceOf(missing.equals("venue") ? VenueNotFoundException.class : VenueBookingNotFoundException.class);
        verify(records,never()).rejectPending(any(),any(),any(),any(),any(),any()); noSideEffects();
    }
}
