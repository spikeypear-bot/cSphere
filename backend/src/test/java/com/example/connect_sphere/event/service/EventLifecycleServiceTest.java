package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.dto.EventReadinessDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.VenueBecameUnavailable;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EC08 (send for safety review, withdraw, confirm, return to Planning) and the
 * Safety Officer decision hook, with every repository mocked and the clock
 * fixed. Each test is named after the acceptance criterion it checks.
 */
@Tag("unit")
class EventLifecycleServiceTest {

    @Mock private EventRepository events;
    @Mock private VenueBookingRecordRepository bookings;
    @Mock private EquipmentRequestRepository equipment;
    @Mock private EventRequestRepository eventRequests;
    @Mock private AvailabilityService availability;
    @Mock private ActivityService activity;
    @Mock private NotificationService notifications;
    @Mock private UserRepository users;

    private EventLifecycleService service;

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR = UUID.randomUUID();
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID VS_USER = UUID.randomUUID();
    private static final UUID TECH_USER = UUID.randomUUID();

    private final List<VenueBookingRecord> eventBookings = new ArrayList<>();
    private final List<EquipmentRequest> eventEquipment = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EventLifecycleService(events, bookings, equipment, eventRequests, availability, activity,
                notifications, users, new JourneyStatusResolver(events, clock), clock);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.findForEvent(any())).thenReturn(eventBookings);
        when(equipment.findByEventId(any())).thenReturn(eventEquipment);
        EventRequest origin = new EventRequest();
        origin.setRequestId(UUID.randomUUID());
        origin.setCreatedBy(ORGANISER);
        when(eventRequests.findByEventId(any())).thenReturn(Optional.of(origin));
        when(users.findByRole(UserRole.vs)).thenReturn(List.of(user(VS_USER)));
        when(users.findByRole(UserRole.technician)).thenReturn(List.of(user(TECH_USER)));
    }

    // ---- readiness checklist ------------------------------------------

    @Test
    void anEventWithNoVenueBookingIsNotReadyAndSaysSo() {
        Event event = stored(EventStatus.pending);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").state()).isEqualTo("outstanding");
        assertThat(readiness.blockers()).contains("No venue has been booked yet.");
    }

    @Test
    void aBookingStillPendingWithVenueStaffBlocksSending() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        booking(VenueBookingStatus.pending);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").detail()).isEqualTo("1 booking is still waiting for Venue Staff.");
    }

    @Test
    void aBookingWhoseVenueBecameUnavailableBlocksSending() {
        Event event = stored(EventStatus.pending);
        VenueBookingRecord affected = booking(VenueBookingStatus.approved);
        when(availability.affected(affected.getBookingId())).thenReturn(true);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").detail()).isEqualTo("1 venue has become unavailable. Request a replacement.");
    }

    @Test
    void rejectedOrCancelledBookingsDoNotBlockWhenAnotherIsApproved() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.rejected);
        booking(VenueBookingStatus.cancelled);
        booking(VenueBookingStatus.approved);

        assertThat(service.readiness(COORDINATOR, event.getEventId()).canSendForReview()).isTrue();
    }

    @Test
    void everyBookingOfAMultiVenueEventMustBeApproved() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        booking(VenueBookingStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isTrue();
        assertThat(item(readiness, "venues").detail()).isEqualTo("2 venue bookings approved.");
    }

    @Test
    void statedEquipmentRequirementsWithNoRequestBlockSending() {
        Event event = stored(EventStatus.pending);
        event.setEquipmentRequirements("Two projectors");
        booking(VenueBookingStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "equipment").detail())
                .isEqualTo("The organiser listed equipment requirements, but no equipment has been requested.");
    }

    @Test
    void anEquipmentRequestStillProcessingBlocksSending() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        equipmentRequest(EquipmentRequestStatus.processing);

        assertThat(service.readiness(COORDINATOR, event.getEventId()).canSendForReview()).isFalse();
    }

    @Test
    void noEquipmentRequirementsAndNoRequestNeedsNoEquipmentApproval() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(item(readiness, "equipment").state()).isEqualTo("not_needed");
        assertThat(readiness.canSendForReview()).isTrue();
    }

    @Test
    void theReadinessChecklistIsOnlyForTheAssignedCoordinator() {
        Event event = stored(EventStatus.pending);

        assertThatThrownBy(() -> service.readiness(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
    }

    // ---- send for safety review ---------------------------------------

    @Test
    void sendingAReadyEventMovesItToAwaitingSafetyReviewAndRecordsIt() {
        Event event = readyEvent(EventStatus.pending);

        EventReadinessDto result = service.sendForSafetyReview(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);
        assertThat(result.journeyStatus().label()).isEqualTo("Awaiting Safety Review");
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.sent_for_safety_review);
        assertThat(entry.getValue().actorUserId()).isEqualTo(COORDINATOR);
        assertThat(entry.getValue().fromStatus()).isEqualTo("PLANNING");
        assertThat(entry.getValue().toStatus()).isEqualTo("AWAITING_SAFETY_REVIEW");
    }

    @Test
    void anEventCanBeSentAgainAfterTheSafetyOfficerAskedForChanges() {
        Event event = readyEvent(EventStatus.safety_changes_requested);
        event.setSafetyFeedback("Add a second exit.");

        service.sendForSafetyReview(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);
        assertThat(event.getSafetyFeedback()).isNull();
    }

    @Test
    void sendingAnEventThatIsNotReadyListsEveryBlockerAndChangesNothing() {
        Event event = stored(EventStatus.pending);
        event.setEquipmentRequirements("Microphones");

        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventNotReadyException.class)
                .satisfies(ex -> assertThat(((EventNotReadyException) ex).getBlockers()).hasSize(2));
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        verifyNoInteractions(activity);
    }

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"awaiting_safety_review", "safety_approved", "confirmed", "cancelled"})
    void onlyAnEventInPlanningCanBeSent(EventStatus status) {
        Event event = readyEvent(status);

        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class);
        assertThat(event.getStatus()).isEqualTo(status);
        verifyNoInteractions(activity);
    }

    @Test
    void anEventWhoseEndHasPassedCannotBeSent() {
        Event event = readyEvent(EventStatus.pending);
        event.setStartDatetime(OffsetDateTime.parse("2026-12-30T09:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2026-12-30T12:00:00Z"));

        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventNotReadyException.class)
                .satisfies(ex -> assertThat(((EventNotReadyException) ex).getBlockers())
                        .contains("This event's end date and time has already passed."));
    }

    @Test
    void aCoordinatorNotAssignedCannotSend() {
        Event event = readyEvent(EventStatus.pending);

        assertThatThrownBy(() -> service.sendForSafetyReview(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
    }

    // ---- withdraw ---------------------------------------------------------

    @Test
    void withdrawingReturnsTheEventToPlanningWithTheReasonRecorded() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), "  Organiser changed the date  ");

        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.safety_review_withdrawn);
        assertThat(entry.getValue().message()).isEqualTo("Organiser changed the date");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void withdrawingNeedsAReason(String reason) {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), reason))
                .isInstanceOf(EventLifecycleValidationException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);
    }

    @Test
    void aReasonOf2001CharactersIsRefused() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), "x".repeat(2001)))
                .isInstanceOf(EventLifecycleValidationException.class);
    }

    @Test
    void onlyAnEventAwaitingReviewCanBeWithdrawn() {
        Event event = readyEvent(EventStatus.safety_approved);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), "Changed"))
                .isInstanceOf(EventLifecycleException.class);
    }

    // ---- confirm --------------------------------------------------------

    @Test
    void confirmingASafetyApprovedEventConfirmsItAndRecordsWhoAndWhen() {
        Event event = readyEvent(EventStatus.safety_approved);

        service.confirm(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        assertThat(event.getConfirmedBy()).isEqualTo(COORDINATOR);
        assertThat(event.getConfirmedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(event.getFirstConfirmedAt()).isEqualTo(event.getConfirmedAt());
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.event_confirmed);
    }

    @Test
    void reConfirmingKeepsTheFirstConfirmationDate() {
        Event event = readyEvent(EventStatus.safety_approved);
        OffsetDateTime first = OffsetDateTime.parse("2026-11-01T09:00:00Z");
        event.setFirstConfirmedAt(first);

        service.confirm(COORDINATOR, event.getEventId());

        assertThat(event.getFirstConfirmedAt()).isEqualTo(first);
        assertThat(event.getConfirmedAt()).isAfter(first);
    }

    @Test
    void confirmingNotifiesTheOrganiserAndTheVenueAndTechnicalStaffInvolved() {
        Event event = readyEvent(EventStatus.safety_approved);
        equipmentRequest(EquipmentRequestStatus.approved);

        service.confirm(COORDINATOR, event.getEventId());

        verify(notifications).createStatusChangeNotification(eq(ORGANISER), isNull(), eq(event.getEventId()),
                eq("Town Hall"), eq("confirmed"), isNull());
        verify(notifications).createEventConfirmedNotifications(eq(List.of(VS_USER, TECH_USER)),
                eq(event.getEventId()), eq("Town Hall"), any());
    }

    @Test
    void technicalSupportIsNotToldWhenTheEventHasNoApprovedEquipment() {
        Event event = readyEvent(EventStatus.safety_approved);

        service.confirm(COORDINATOR, event.getEventId());

        verify(notifications).createEventConfirmedNotifications(eq(List.of(VS_USER)), any(), any(), any());
    }

    @Test
    void anEventCannotBeConfirmedBeforeTheSafetyOfficerApproves() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessageContaining("after the Safety Officer approves");
        verifyNoInteractions(notifications);
    }

    @Test
    void confirmingTwiceSaysItIsAlreadyConfirmedAndNotifiesNoOneAgain() {
        Event event = readyEvent(EventStatus.confirmed);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessage("This event is already confirmed.");
        verifyNoInteractions(notifications, activity);
    }

    @Test
    void prerequisitesAreReCheckedAtTheMomentOfConfirming() {
        Event event = readyEvent(EventStatus.safety_approved);
        when(availability.affected(eventBookings.get(0).getBookingId())).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventNotReadyException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
        verifyNoInteractions(notifications);
    }

    @Test
    void aCoordinatorNotAssignedCannotConfirm() {
        Event event = readyEvent(EventStatus.safety_approved);

        assertThatThrownBy(() -> service.confirm(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
    }

    // ---- Safety Officer decisions ---------------------------------------

    @Test
    void approvalMovesTheEventToSafetyApprovedAndRecordsWhen() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.APPROVE, null);

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
        assertThat(event.getSafetyApprovedAt()).isNotNull();
    }

    @Test
    void requestingChangesNeedsFeedbackAndShowsItToTheCoordinator() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.recordSafetyDecision(OFFICER, event.getEventId(),
                EventLifecycleService.SafetyDecision.REQUEST_CHANGES, " "))
                .isInstanceOf(EventLifecycleValidationException.class);

        service.recordSafetyDecision(OFFICER, event.getEventId(),
                EventLifecycleService.SafetyDecision.REQUEST_CHANGES, "Add a second exit.");
        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_changes_requested);
        assertThat(event.getSafetyFeedback()).isEqualTo("Add a second exit.");
    }

    @Test
    void aSafetyRejectionSendsTheEventBackToTheCoordinatorRatherThanCancellingIt() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.REJECT,
                "Capacity too tight for the layout.");

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_changes_requested);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.safety_rejected);
    }

    // ---- automatic return to Planning ------------------------------------

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"awaiting_safety_review", "safety_approved", "confirmed"})
    void aVenueBecomingUnavailableReturnsTheEventToPlanningAndTellsTheCoordinator(EventStatus status) {
        Event event = readyEvent(status);
        event.setSafetyApprovedAt(OffsetDateTime.parse("2026-12-01T00:00:00Z"));

        service.onVenueBecameUnavailable(new VenueBecameUnavailable(List.of(event.getEventId()), VS_USER,
                "A venue for this event became unavailable: flooding"));

        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        assertThat(event.getSafetyApprovedAt()).isNull();
        verify(notifications).createReturnedToPlanningNotification(COORDINATOR, event.getEventId(), "Town Hall",
                "A venue for this event became unavailable: flooding");
    }

    @Test
    void anEventAlreadyInPlanningIsLeftAsItIs() {
        Event event = readyEvent(EventStatus.pending);

        assertThat(service.returnToPlanning(event.getEventId(), VS_USER, "x")).isFalse();
        verifyNoInteractions(activity, notifications);
    }

    @Test
    void aCompletedEventIsNeverReopened() {
        Event event = readyEvent(EventStatus.confirmed);
        event.setStartDatetime(OffsetDateTime.parse("2026-12-30T09:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2026-12-30T12:00:00Z"));

        assertThat(service.returnToPlanning(event.getEventId(), VS_USER, "x")).isFalse();
        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        verify(notifications, never()).createReturnedToPlanningNotification(any(), any(), any(), any());
    }

    // ---- helpers ----------------------------------------------------------

    private Event stored(EventStatus status) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setStatus(status);
        event.setCoordinatorId(COORDINATOR);
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T01:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T04:00:00Z"));
        event.setExpectedAttendance(150);
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    /** An event in {@code status} with one approved booking and no equipment needs. */
    private Event readyEvent(EventStatus status) {
        Event event = stored(status);
        booking(VenueBookingStatus.approved);
        return event;
    }

    private VenueBookingRecord booking(VenueBookingStatus status) {
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setStatus(status);
        eventBookings.add(booking);
        return booking;
    }

    private void equipmentRequest(EquipmentRequestStatus status) {
        EquipmentRequest request = mock(EquipmentRequest.class);
        when(request.getStatus()).thenReturn(status);
        eventEquipment.add(request);
    }

    private static EventReadinessDto.Item item(EventReadinessDto readiness, String key) {
        return readiness.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private static User user(UUID id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }
}
