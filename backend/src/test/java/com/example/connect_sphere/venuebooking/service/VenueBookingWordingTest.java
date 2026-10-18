package com.example.connect_sphere.venuebooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityModels;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.dto.SubmitVenueBookingRequest;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;

/**
 * EC03 fixes from Sprint 2 testing: booking notes up to the 2000-character
 * limit are accepted, a recorded unavailable period is named in the refusal,
 * and messages use the labels people see rather than stored codes.
 */
@Tag("unit")
@Tag("EC03")
class VenueBookingWordingTest {

    @Mock private EventRepository events;
    @Mock private VenueRepository venues;
    @Mock private VenueBookingRecordRepository bookings;
    @Mock private EventRequestRepository eventRequests;
    @Mock private UserRepository users;
    @Mock private VenueMapper venueMapper;
    @Mock private ActivityService activityService;
    @Mock private NotificationService notificationService;
    @Mock private AvailabilityService availability;
    @Mock private VenueOperationalIssueRepository operationalIssues;

    private VenueBookingRequestService service;
    private static final UUID COORDINATOR = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VenueBookingRequestService(events, venues, bookings, eventRequests, users, venueMapper,
                activityService, notificationService, operationalIssues, null, availability, null);
        when(bookings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(availability.settings(any())).thenReturn(new AvailabilityModels.Settings(0, 0));
        when(bookings.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of());
        when(operationalIssues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(any())).thenReturn(List.of());
    }

    @ParameterizedTest
    @EnumSource(value = ActivityType.class, names = {"venue_booking_requested", "venue_added", "venue_booking_replaced"})
    void notesOfExactlyTheMaximumLengthAreAcceptedAndTheTimelineEntryStillFits(ActivityType type) {
        Event event = event(EventStatus.pending, List.of("none"), List.of());
        Venue venue = venue(List.of(), List.of());
        EventRequest origin = new EventRequest();
        origin.setRequestId(UUID.randomUUID());
        when(eventRequests.findByEventId(event.getEventId())).thenReturn(Optional.of(origin));
        String notes = "x".repeat(VenueBookingRequestService.MAX_TEXT_LENGTH);
        UUID replaces = null;
        if (type != ActivityType.venue_booking_requested) {
            VenueBookingRecord existing = new VenueBookingRecord();
            existing.setBookingId(UUID.randomUUID());
            existing.setEventId(event.getEventId());
            existing.setVenueId(UUID.randomUUID());
            existing.setStatus(VenueBookingStatus.approved);
            when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(existing));
            if (type == ActivityType.venue_booking_replaced) {
                replaces = existing.getBookingId();
                when(bookings.findById(replaces)).thenReturn(Optional.of(existing));
                when(availability.affected(replaces)).thenReturn(true);
            }
        }

        var booking = service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), notes, null, replaces));

        assertThat(booking.bookingNotes()).isEqualTo(notes);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(type);
        assertThat(entry.getValue().message()).hasSizeLessThanOrEqualTo(2000)
                .startsWith("Requested 1 Harbour Road. xxx").endsWith("…");
    }

    @Test
    void shortNotesAreCopiedToTheTimelineUnchanged() {
        assertThat(VenueBookingRequestService.requestedMessage("1 Harbour Road", "Stage needed"))
                .isEqualTo("Requested 1 Harbour Road. Stage needed");
        assertThat(VenueBookingRequestService.requestedMessage("1 Harbour Road", null)).isEqualTo("Requested 1 Harbour Road");
    }

    @Test
    void aRecordedUnavailablePeriodIsNamedWithItsTimesAndReason() {
        Event event = event(EventStatus.pending, List.of("none"), List.of());
        Venue venue = venue(List.of(), List.of());
        when(availability.unavailablePeriods(eq(venue.getVenueId()), any(), any(), any())).thenReturn(List.of(
                new AvailabilityModels.Period(UUID.randomUUID(), venue.getVenueId(),
                        OffsetDateTime.parse("2027-03-10T08:00:00+08:00"), OffsetDateTime.parse("2027-03-10T18:00:00+08:00"),
                        "Carpet replacement", OffsetDateTime.now())));

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .hasMessage("This venue is unavailable during the event, setup or turnaround period "
                        + "(10 Mar 2027, 08:00 am – 10 Mar 2027, 06:00 pm (Singapore time): Carpet replacement). Choose another venue.");
    }

    @Test
    void aMissingAccessibilityFeatureIsNamedByItsLabel() {
        Event event = event(EventStatus.pending, List.of("wheelchair_support"), List.of());
        Venue venue = venue(List.of("elevators"), List.of());

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .hasMessage("This venue does not offer Wheelchair support. Explain why it should still be considered, for Venue Staff to judge.");
    }

    @Test
    void aMissingFacilityIsNamedByItsLabel() {
        Event event = event(EventStatus.pending, List.of("none"), List.of("stage", "audio_visual_equipment"));
        Venue venue = venue(List.of(), List.of("projection"));

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .hasMessage("This venue does not offer the required facilities: Stage, Audio-visual equipment.");
    }

    @Test
    void anEventPastPlanningIsDescribedByItsStatusLabel() {
        Event event = event(EventStatus.awaiting_safety_review, List.of("none"), List.of());
        Venue venue = venue(List.of(), List.of());

        assertThatThrownBy(() -> service.submit(COORDINATOR, event.getEventId(),
                new SubmitVenueBookingRequest(venue.getVenueId(), null, null)))
                .hasMessage("Venue booking requests can only be made while an event is in Planning (this event is Awaiting Safety Review).");
    }

    @Test
    void labelsMatchTheScreensAndUnknownCodesPassThrough() {
        assertThat(AccessibilityFeature.labelOf("step_free_access")).isEqualTo("Step-free access");
        assertThat(AccessibilityFeature.labelOf("public_transport")).isEqualTo("Near public transport");
        assertThat(Facility.labelOf("barbeque_pit")).isEqualTo("Barbeque pit");
        assertThat(Facility.labelOf("helipad")).isEqualTo("helipad");
    }

    private Event event(EventStatus status, List<String> accessibility, List<String> facilities) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        event.setExpectedAttendance(100);
        event.setAccessibilityNeeds(accessibility);
        event.setRequiredFacilities(facilities);
        event.setCoordinatorId(COORDINATOR);
        event.setStatus(status);
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private Venue venue(List<String> accessibility, List<String> facilities) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress("1 Harbour Road");
        venue.setVenueCapacity(200);
        venue.setVenueAccessibilities(accessibility);
        venue.setVenueFacilities(facilities);
        when(venues.findById(eq(venue.getVenueId()))).thenReturn(Optional.of(venue));
        when(venues.findForUpdate(eq(venue.getVenueId()))).thenReturn(Optional.of(venue));
        return venue;
    }
}
