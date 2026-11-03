package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;

/**
 * EC03 rules that the rolled-back flow test cannot observe (notifications are
 * sent after commit), plus the suitability boundaries in isolation.
 */
@Tag("unit") @Tag("VS17") @Tag("EC-C2")
@Tag("EC03") @Tag("VS25")
class VenueBookingRequestServiceTest {

    @Mock private EventRepository events;
    @Mock private VenueRepository venues;
    @Mock private VenueBookingRecordRepository bookings;
    @Mock private EventRequestRepository eventRequests;
    @Mock private UserRepository users;
    // The real mapper: with no hours service it reports "Operating hours not configured".
    private final VenueMapper venueMapper = new com.example.connect_sphere.venue.mapper.VenueMapperImpl();
    @Mock private ActivityService activityService;
    @Mock private NotificationService notificationService;
    @Mock private com.example.connect_sphere.venueavailability.service.AvailabilityService availability;
    @Mock private VenueOperationalIssueRepository operationalIssues;

    private VenueBookingRequestService service;

    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID VS_ONE = UUID.randomUUID();
    private static final UUID VS_TWO = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VenueBookingRequestService(events, venues, bookings, eventRequests, users, venueMapper,
                activityService, notificationService, operationalIssues, null, availability,
                org.mockito.Mockito.mock(com.example.connect_sphere.event.service.EventLifecycleService.class), Clock.systemUTC());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"plan",org.mockito.Mockito.mock(BookingPlanService.class));
        when(bookings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(availability.settings(any())).thenReturn(new com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Settings(0, 0));
        when(bookings.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of());
        when(operationalIssues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(any())).thenReturn(List.of());
        when(users.findByRole(UserRole.vs)).thenReturn(List.of(user(VS_ONE), user(VS_TWO)));
    }

    @Test
    @DisplayName("EC03 (happy): every venue staff member is notified when a coordinator requests a venue")
    void everyVenueStaffMemberIsNotifiedOfTheNewRequest() {
        Event event = event(150, List.of("none"));
        Venue venue = venue(150, List.of());

        var booking = service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null));

        verify(notificationService).createVenueBookingRequestedNotifications(
                List.of(VS_ONE, VS_TWO), booking.bookingId(), event.getEventId(), "Town Hall");
    }

    @Test
    @DisplayName("EC03 (happy): the venue request and its notes appear on the original event request's timeline")
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

    @Test
    @DisplayName("EC03 (happy): the coordinator's booking history shows the rejection reason and the suggested alternative venue and arrangement")
    void coordinatorBookingHistoryIncludesRejectedAlternativeSuggestions() {
        Event event = event(150, List.of("none"));
        Venue requestedVenue = venue(150, List.of());
        Venue alternativeVenue = venue(200, List.of());
        alternativeVenue.setVenueAddress("2 Garden Road");
        VenueBookingRecord rejected = new VenueBookingRecord();
        rejected.setBookingId(UUID.randomUUID());
        rejected.setVenueId(requestedVenue.getVenueId());
        rejected.setEventId(event.getEventId());
        rejected.setStatus(VenueBookingStatus.rejected);
        rejected.setRejectReason("Unavailable on that date");
        rejected.setAlternativeVenueId(alternativeVenue.getVenueId());
        rejected.setAlternativeArrangement("Move to the afternoon");
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(rejected));

        var result = service.bookingsForEvent(COORDINATOR, event.getEventId()).get(0);

        assertThat(result.rejectReason()).isEqualTo("Unavailable on that date");
        assertThat(result.alternativeVenueId()).isEqualTo(alternativeVenue.getVenueId());
        assertThat(result.alternativeVenueAddress()).isEqualTo("2 Garden Road");
        assertThat(result.alternativeArrangement()).isEqualTo("Move to the afternoon");
    }

    // ---- VenueSuitability boundaries -----------------------------------

    @Test
    @DisplayName("EC03 (boundary): an event whose attendance equals the venue capacity fits")
    void attendanceEqualToCapacityFits() {
        assertThat(VenueSuitability.of(event(150, List.of()), venue(150, List.of())).capacityOk()).isTrue();
    }

    @Test
    @DisplayName("EC03 (boundary): an event one person over the venue capacity does not fit")
    void attendanceOneOverCapacityDoesNotFit() {
        assertThat(VenueSuitability.of(event(151, List.of()), venue(150, List.of())).capacityOk()).isFalse();
    }

    @Test
    @DisplayName("EC03 (boundary): an event with no accessibility needs never needs a justification")
    void noAccessibilityNeededNeverRequiresAJustification() {
        var suitability = VenueSuitability.of(event(10, List.of("none")), venue(50, List.of()));

        assertThat(suitability.needsJustification()).isFalse();
    }

    @Test
    @DisplayName("EC03 (negative): only the accessibility features the venue lacks are reported and a justification is needed")
    void onlyTheAccessibilityFeaturesTheVenueLacksAreReported() {
        var suitability = VenueSuitability.of(
                event(10, List.of("step_free_access", "elevators")), venue(50, List.of("elevators")));

        assertThat(suitability.missingAccessibility()).containsExactly("step_free_access");
        assertThat(suitability.needsJustification()).isTrue();
    }

    @Test
    @DisplayName("EC03 (negative): only the facilities the venue lacks are reported")
    void onlyTheFacilitiesTheVenueLacksAreReported() {
        Event event = event(10, List.of());
        event.setRequiredFacilities(List.of("stage", "projection"));
        Venue venue = venue(50, List.of());
        venue.setVenueFacilities(List.of("stage"));

        var suitability = VenueSuitability.of(event, venue);

        assertThat(suitability.missingFacilities()).containsExactly("projection");
        assertThat(suitability.facilitiesOk()).isFalse();
    }

    @Test
    @DisplayName("EC03 (conflict): a venue issue that overlaps the event blocks the request and names the issue")
    void operationalIssueOverlappingEventBlocksSubmission() {
        Event event = event(10, List.of());
        Venue venue = venue(50, List.of());
        when(venues.findAll()).thenReturn(List.of(venue));
        VenueOperationalIssue issue = new VenueOperationalIssue(venue.getVenueId(), "Emergency maintenance",
                OffsetDateTime.parse("2027-03-10T10:00:00+08:00"),
                OffsetDateTime.parse("2027-03-10T11:00:00+08:00"), VS_ONE);
        when(operationalIssues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(any()))
                .thenReturn(List.of(issue));

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .isInstanceOf(InvalidVenueBookingException.class)
                .hasMessageContaining("Emergency maintenance");
    }

    @Test
    @DisplayName("EC03 (negative): a submission with no request at all is refused as having no venue, and nothing is saved")
    void aMissingRequestIsRefused() {
        Event event = event(150, List.of("none"));

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(), null))
                .isInstanceOf(InvalidVenueBookingException.class)
                .hasMessage("Select a venue before submitting the booking request.");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("EC03 (negative): a caller with no coordinator identity is treated as not assigned, and nothing is saved")
    void aMissingCoordinatorIsNotAssigned() {
        Event event = event(150, List.of("none"));
        Venue venue = venue(150, List.of());

        assertThatThrownBy(() -> service.submit(null, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .isInstanceOf(com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException.class);
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("EC-C2 (negative): a batch with no booking list at all is refused like an empty one")
    void aMissingBatchIsRefused() {
        Event event = event(150, List.of("none"));

        for (var batch : java.util.Arrays.asList(null, new SubmitVenueBookingRequest.Batch(null)))
            assertThatThrownBy(() -> service.submitBatch(COORDINATOR, event.getEventId(), batch))
                    .isInstanceOf(InvalidVenueBookingException.class)
                    .hasMessage("Add at least one venue before submitting.");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("VS25 (boundary): cancelling with no Venue Staff accounts still cancels the booking and sends no notice")
    void cancellingWithNoVenueStaffSendsNoNotice() {
        Event event = event(150, List.of("none"));
        Venue venue = venue(150, List.of());
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setVenueId(venue.getVenueId());
        booking.setEventId(event.getEventId());
        booking.setStatus(VenueBookingStatus.pending);
        when(bookings.findById(booking.getBookingId())).thenReturn(Optional.of(booking));
        when(users.findByRole(UserRole.vs)).thenReturn(List.of());

        var result = service.cancel(COORDINATOR, event.getEventId(), booking.getBookingId(), "Not needed");

        assertThat(result.status()).isEqualTo(VenueBookingStatus.cancelled);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
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
        when(venues.findForUpdate(eq(venue.getVenueId()))).thenReturn(Optional.of(venue));
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
