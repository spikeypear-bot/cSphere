package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.hold.VenueHoldService;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EO07 AC8, AC10, AC12–AC14: {@link EventLifecycleService#cancel}, the only
 * place an event becomes Cancelled, with every collaborator mocked and the
 * clock fixed. Venue holds and notifications are mocks because the
 * requirement is the interaction (holds released, the right staff told once).
 */
@Tag("unit")
@Tag("EO07")
class EventLifecycleCancellationTest {

    @Mock private EventRepository events;
    @Mock private VenueBookingRecordRepository bookings;
    @Mock private EquipmentRequestRepository equipment;
    @Mock private EventRequestRepository eventRequests;
    @Mock private AvailabilityService availability;
    @Mock private ActivityService activity;
    @Mock private NotificationService notifications;
    @Mock private UserRepository users;
    @Mock private VenueHoldService holds;

    private EventLifecycleService service;

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID VS_USER = UUID.randomUUID();
    private static final UUID TECH_USER = UUID.randomUUID();
    private static final String MESSAGE = "Cancelled at the organiser's request: Called off";

    private final List<VenueBookingRecord> eventBookings = new ArrayList<>();
    private final List<EquipmentRequest> eventEquipment = new ArrayList<>();
    private Event event;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EventLifecycleService(events, bookings, equipment, eventRequests, availability, activity,
                notifications, users, new JourneyStatusResolver(events, clock), clock, holds);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.findForEvent(any())).thenReturn(eventBookings);
        when(equipment.findByEventId(any())).thenReturn(eventEquipment);
        EventRequest origin = new EventRequest();
        origin.setRequestId(UUID.randomUUID());
        when(eventRequests.findByEventId(any())).thenReturn(Optional.of(origin));
        when(users.findByRole(UserRole.vs)).thenReturn(List.of(user(VS_USER)));
        when(users.findByRole(UserRole.technician)).thenReturn(List.of(user(TECH_USER)));
        event = stored(EventStatus.confirmed);
    }

    private static User user(UUID id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }

    private Event stored(EventStatus status) {
        Event e = new Event();
        e.setEventId(UUID.randomUUID());
        e.setEventName("Town Hall");
        e.setStatus(status);
        e.setCoordinatorId(COORDINATOR);
        e.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00:00+08:00"));
        e.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00:00+08:00"));
        when(events.findForUpdate(e.getEventId())).thenReturn(Optional.of(e));
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
        return e;
    }

    private VenueBookingRecord booking(VenueBookingStatus status) {
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setEventId(event.getEventId());
        booking.setStatus(status);
        eventBookings.add(booking);
        return booking;
    }

    private void equipmentRequest(EquipmentRequestStatus status) {
        EquipmentRequest request = org.mockito.Mockito.mock(EquipmentRequest.class);
        when(request.getStatus()).thenReturn(status);
        eventEquipment.add(request);
    }

    @Test
    @DisplayName("EO07 AC8 (happy): cancelling cancels the pending and approved bookings, releases their holds, and leaves rejected and cancelled ones as history")
    void cancellingReleasesActiveBookingsOnly() {
        VenueBookingRecord pending = booking(VenueBookingStatus.pending);
        VenueBookingRecord approved = booking(VenueBookingStatus.approved);
        VenueBookingRecord rejected = booking(VenueBookingStatus.rejected);
        VenueBookingRecord alreadyCancelled = booking(VenueBookingStatus.cancelled);

        EventLifecycleService.Cancellation result = service.cancel(COORDINATOR, event.getEventId(), MESSAGE);

        assertThat(event.getStatus()).isEqualTo(EventStatus.cancelled);
        assertThat(pending.getStatus()).isEqualTo(VenueBookingStatus.cancelled);
        assertThat(approved.getStatus()).isEqualTo(VenueBookingStatus.cancelled);
        assertThat(rejected.getStatus()).isEqualTo(VenueBookingStatus.rejected);
        assertThat(alreadyCancelled.getStatus()).isEqualTo(VenueBookingStatus.cancelled);
        assertThat(result.cancelledBookingIds()).containsExactly(pending.getBookingId(), approved.getBookingId());
        verify(holds).endForBooking(pending.getBookingId(), COORDINATOR);
        verify(holds).endForBooking(approved.getBookingId(), COORDINATOR);
        verify(holds, never()).endForBooking(eq(rejected.getBookingId()), any());
        verify(holds, never()).endForBooking(eq(alreadyCancelled.getBookingId()), any());
        verify(bookings, never()).save(rejected);
        verify(bookings, never()).save(alreadyCancelled);
    }

    @Test
    @DisplayName("EO07 AC11 (happy): the cancellation is on the event's timeline as a Major change, from its status to Cancelled, with the reason; the event row is kept")
    void theCancellationIsRecordedAsMajor() {
        service.cancel(COORDINATOR, event.getEventId(), MESSAGE);

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.event_cancelled);
        assertThat(entry.getValue().type().major()).isTrue();
        assertThat(entry.getValue().actorUserId()).isEqualTo(COORDINATOR);
        assertThat(entry.getValue().message()).isEqualTo(MESSAGE);
        assertThat(entry.getValue().fromStatus()).isEqualTo("CONFIRMED");
        assertThat(entry.getValue().toStatus()).isEqualTo("CANCELLED");
        verify(events, never()).delete(any());
        verify(events, never()).deleteById(any());
    }

    @Test
    @DisplayName("EO07 AC10 (happy): Venue Staff are told once when bookings were cancelled, and Technical Support when equipment had been approved")
    void venueStaffAndTechnicalSupportAreToldOnce() {
        booking(VenueBookingStatus.approved);
        equipmentRequest(EquipmentRequestStatus.approved);

        service.cancel(COORDINATOR, event.getEventId(), MESSAGE);

        verify(notifications, times(1)).createEventCancelledNotifications(eq(List.of(VS_USER, TECH_USER)),
                eq(event.getEventId()), eq("Town Hall"), anyString());
    }

    @Test
    @DisplayName("EO07 AC10 (boundary): Technical Support are not told when no equipment was approved (only requested or rejected)")
    void technicalSupportAreNotToldWithoutApprovedEquipment() {
        booking(VenueBookingStatus.pending);
        equipmentRequest(EquipmentRequestStatus.processing);
        equipmentRequest(EquipmentRequestStatus.rejected);

        service.cancel(COORDINATOR, event.getEventId(), MESSAGE);

        verify(notifications, times(1)).createEventCancelledNotifications(eq(List.of(VS_USER)),
                eq(event.getEventId()), eq("Town Hall"), anyString());
    }

    @Test
    @DisplayName("EO07 AC10 (boundary): with no active bookings and no approved equipment, no staff are told")
    void noStaffAreToldWhenNothingWasArranged() {
        booking(VenueBookingStatus.rejected);

        service.cancel(COORDINATOR, event.getEventId(), MESSAGE);

        verify(notifications, never()).createEventCancelledNotifications(anyList(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved", "confirmed"})
    @DisplayName("EO07 AC3, AC8 (happy): an event anywhere from Planning to Confirmed can be cancelled")
    void anActiveEventCanBeCancelled(EventStatus status) {
        Event active = stored(status);

        service.cancel(COORDINATOR, active.getEventId(), MESSAGE);

        assertThat(active.getStatus()).isEqualTo(EventStatus.cancelled);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"cancelled", "completed"})
    @DisplayName("EO07 AC12, AC13 (conflict): a Cancelled or Completed event cannot be cancelled again; nothing changes and nobody is told")
    void aFinishedEventCannotBeCancelledAgain(EventStatus status) {
        Event finished = stored(status);
        booking(VenueBookingStatus.approved);

        assertThatThrownBy(() -> service.cancel(COORDINATOR, finished.getEventId(), MESSAGE))
                .isInstanceOf(EventLifecycleException.class);

        assertThat(finished.getStatus()).isEqualTo(status);
        verify(events, never()).save(any());
        verify(bookings, never()).save(any());
        verifyNoInteractions(holds, activity, notifications);
    }

    @Test
    @DisplayName("EO07 AC14 (failure): if saving the event fails, nobody is notified")
    void aFailedCancellationSendsNoNotification() {
        booking(VenueBookingStatus.approved);
        when(events.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.cancel(COORDINATOR, event.getEventId(), MESSAGE))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activity, notifications);
    }
}
