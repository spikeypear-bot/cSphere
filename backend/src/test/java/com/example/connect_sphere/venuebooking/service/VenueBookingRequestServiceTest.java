package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EC03 rules that the rolled-back flow test cannot observe (notifications are
 * sent after commit), plus the suitability boundaries in isolation.
 */
class VenueBookingRequestServiceTest {

    @Mock private EventRepository events;
    @Mock private VenueRepository venues;
    @Mock private VenueBookingRecordRepository bookings;
    @Mock private EventRequestRepository eventRequests;
    @Mock private UserRepository users;
    @Mock private VenueMapper venueMapper;
    @Mock private ActivityService activityService;
    @Mock private NotificationService notificationService;

    private VenueBookingRequestService service;

    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID VS_ONE = UUID.randomUUID();
    private static final UUID VS_TWO = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VenueBookingRequestService(events, venues, bookings, eventRequests, users, venueMapper,
                activityService, notificationService);
        when(bookings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.findConfirmedOverlapping(any(), any(), any())).thenReturn(List.of());
        when(users.findByRole(UserRole.vs)).thenReturn(List.of(user(VS_ONE), user(VS_TWO)));
    }

    @Test
    void everyVenueStaffMemberIsNotifiedOfTheNewRequest() {
        Event event = event(150, List.of("none"));
        Venue venue = venue(150, List.of());

        var booking = service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null));

        verify(notificationService).createVenueBookingRequestedNotifications(
                List.of(VS_ONE, VS_TWO), booking.bookingId(), event.getEventId(), "Town Hall");
    }

    @Test
    void theRequestIsRecordedOnTheOriginatingRequestsTimeline() {
        Event event = event(150, List.of("none"));
        Venue venue = venue(150, List.of());
        UUID requestId = UUID.randomUUID();
        EventRequest origin = new EventRequest();
        origin.setRequestId(requestId);
        when(eventRequests.findByEventId(event.getEventId())).thenReturn(Optional.of(origin));

        service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), "Stage needed", null));

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().requestId()).isEqualTo(requestId);
        assertThat(entry.getValue().eventId()).isEqualTo(event.getEventId());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.venue_booking_requested);
        assertThat(entry.getValue().message()).isEqualTo("Requested 1 Harbour Road. Stage needed");
    }

    // ---- VenueSuitability boundaries -----------------------------------

    @Test
    void attendanceEqualToCapacityFits() {
        assertThat(VenueSuitability.of(event(150, List.of()), venue(150, List.of())).capacityOk()).isTrue();
    }

    @Test
    void attendanceOneOverCapacityDoesNotFit() {
        assertThat(VenueSuitability.of(event(151, List.of()), venue(150, List.of())).capacityOk()).isFalse();
    }

    @Test
    void noAccessibilityNeededNeverRequiresAJustification() {
        var suitability = VenueSuitability.of(event(10, List.of("none")), venue(50, List.of()));

        assertThat(suitability.needsJustification()).isFalse();
    }

    @Test
    void onlyTheAccessibilityFeaturesTheVenueLacksAreReported() {
        var suitability = VenueSuitability.of(
                event(10, List.of("step_free_access", "elevators")), venue(50, List.of("elevators")));

        assertThat(suitability.missingAccessibility()).containsExactly("step_free_access");
        assertThat(suitability.needsJustification()).isTrue();
    }

    private Event event(int attendance, List<String> accessibility) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        event.setExpectedAttendance(attendance);
        event.setAccessibilityNeeds(accessibility);
        event.setCoordinatorId(COORDINATOR);
        event.setStatus(EventStatus.pending);
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private Venue venue(int capacity, List<String> accessibility) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress("1 Harbour Road");
        venue.setVenueCapacity(capacity);
        venue.setVenueAccessibilities(accessibility);
        when(venues.findById(eq(venue.getVenueId()))).thenReturn(Optional.of(venue));
        return venue;
    }

    private static User user(UUID id) {
        User user = new User();
        user.setUserId(id);
        user.setUsername("vs");
        user.setRole(UserRole.vs);
        return user;
    }
}
