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
import org.junit.jupiter.api.DisplayName;
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
@Tag("EC08")
@Tag("EC-NEW2")
@Tag("ECL-C7")
@Tag("SO-C5")
@Tag("SO-C6")
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
    @DisplayName("EC08 AC3, AC10 (negative): an event with no venue booking is not ready, and the checklist says no venue has been booked")
    void anEventWithNoVenueBookingIsNotReadyAndSaysSo() {
        Event event = stored(EventStatus.pending);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").state()).isEqualTo("outstanding");
        assertThat(readiness.blockers()).contains("No venue has been booked yet.");
    }

    @Test
    @DisplayName("EC08 AC8 (negative): a booking still pending with Venue Staff blocks sending")
    void aBookingStillPendingWithVenueStaffBlocksSending() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        booking(VenueBookingStatus.pending);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").detail()).isEqualTo("1 booking is still waiting for Venue Staff.");
    }

    @Test
    @DisplayName("EC08 AC8 (conflict): a booking whose venue became unavailable blocks sending")
    void aBookingWhoseVenueBecameUnavailableBlocksSending() {
        Event event = stored(EventStatus.pending);
        VenueBookingRecord affected = booking(VenueBookingStatus.approved);
        when(availability.affected(affected.getBookingId())).thenReturn(true);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "venues").detail()).isEqualTo("1 venue has become unavailable. Request a replacement.");
    }

    @Test
    @DisplayName("EC08 AC14 (boundary): rejected or cancelled bookings do not block once another is approved")
    void rejectedOrCancelledBookingsDoNotBlockWhenAnotherIsApproved() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.rejected);
        booking(VenueBookingStatus.cancelled);
        booking(VenueBookingStatus.approved);

        assertThat(service.readiness(COORDINATOR, event.getEventId()).canSendForReview()).isTrue();
    }

    @Test
    @DisplayName("EC08 AC13 (negative): every active booking of a multi-venue event must be approved")
    void everyBookingOfAMultiVenueEventMustBeApproved() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        booking(VenueBookingStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isTrue();
        assertThat(item(readiness, "venues").detail()).isEqualTo("2 venue bookings approved.");
    }

    @Test
    @DisplayName("EC08 AC11 (negative): stated equipment needs with no equipment request block sending")
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
    @DisplayName("EC08 AC9 (negative): an equipment request not yet approved blocks sending")
    void anEquipmentRequestStillProcessingBlocksSending() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);
        equipmentRequest(EquipmentRequestStatus.processing);

        assertThat(service.readiness(COORDINATOR, event.getEventId()).canSendForReview()).isFalse();
    }

    @Test
    @DisplayName("EC08 AC12 (boundary): no stated equipment needs and no request need no equipment approval")
    void noEquipmentRequirementsAndNoRequestNeedsNoEquipmentApproval() {
        Event event = stored(EventStatus.pending);
        booking(VenueBookingStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(item(readiness, "equipment").state()).isEqualTo("not_needed");
        assertThat(readiness.canSendForReview()).isTrue();
    }

    @Test
    @DisplayName("EC08 AC1, AC2 (cross-cutting): only the assigned coordinator can see the readiness checklist")
    void theReadinessChecklistIsOnlyForTheAssignedCoordinator() {
        Event event = stored(EventStatus.pending);

        assertThatThrownBy(() -> service.readiness(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
    }

    // ---- send for safety review ---------------------------------------

    @Test
    @DisplayName("EC08 AC7, AC16, AC17 (happy): sending a ready event moves it to Awaiting Safety Review and records who and when")
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
    @DisplayName("EC08 AC6, AC24 (happy): an event can be sent again after the Safety Officer asked for changes")
    void anEventCanBeSentAgainAfterTheSafetyOfficerAskedForChanges() {
        Event event = readyEvent(EventStatus.safety_changes_requested);
        event.setSafetyFeedback("Add a second exit.");

        service.sendForSafetyReview(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);
        assertThat(event.getSafetyFeedback()).isNull();
    }

    @Test
    @DisplayName("EC08 AC15, AC37, EC-NEW2 AC17 (failure): sending an unready event lists every blocker and changes nothing")
    void sendingAnEventThatIsNotReadyListsEveryBlockerAndChangesNothing() {
        Event event = stored(EventStatus.pending);
        event.setEquipmentRequirements("Microphones");

        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventNotReadyException.class)
                .satisfies(ex -> assertThat(((EventNotReadyException) ex).getBlockers()).hasSize(2));
        // Status, timeline and notifications all unchanged (AC37).
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        verifyNoInteractions(activity, notifications);
    }

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"awaiting_safety_review", "safety_approved", "confirmed", "cancelled"})
    @DisplayName("EC08 AC6, AC34 (negative): only an event in Planning or Safety Changes Requested can be sent")
    void onlyAnEventInPlanningCanBeSent(EventStatus status) {
        Event event = readyEvent(status);

        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class);
        assertThat(event.getStatus()).isEqualTo(status);
        verifyNoInteractions(activity);
    }

    @Test
    @DisplayName("EC08 AC28 (boundary): an event whose end has passed cannot be sent, and says why")
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
    @DisplayName("EC08 AC1, AC2 (cross-cutting): a coordinator not assigned to the event cannot send it, and nothing changes")
    void aCoordinatorNotAssignedCannotSend() {
        Event event = readyEvent(EventStatus.pending);

        assertThatThrownBy(() -> service.sendForSafetyReview(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
    }

    // ---- withdraw ---------------------------------------------------------

    @Test
    @DisplayName("EC08 AC19, AC20 (happy): withdrawing returns the event to Planning with the reason recorded")
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
    @DisplayName("EC08 AC19 (negative): withdrawing needs a reason")
    void withdrawingNeedsAReason(String reason) {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), reason))
                .isInstanceOf(EventLifecycleValidationException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);
        // On this path the repositories are dummies (IS212 Week 9): the
        // service needs them to exist, but a blank reason is refused before
        // any of them is used, so nothing is even loaded, let alone changed.
        verify(events, never()).findForUpdate(any());
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC08 AC19 (boundary): a withdrawal reason of 2001 characters is refused")
    void aReasonOf2001CharactersIsRefused() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), "x".repeat(2001)))
                .isInstanceOf(EventLifecycleValidationException.class);
    }

    @Test
    @DisplayName("EC08 AC34, EC-NEW2 AC23 (conflict): only an event awaiting review can be withdrawn")
    void onlyAnEventAwaitingReviewCanBeWithdrawn() {
        Event event = readyEvent(EventStatus.safety_approved);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), "Changed"))
                .isInstanceOf(EventLifecycleException.class);
    }

    // ---- confirm --------------------------------------------------------

    @Test
    @DisplayName("EC08 AC25, AC29, AC30 (happy): confirming a Safety Approved event confirms it and records who and when")
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
    @DisplayName("EC08 AC33 (negative): confirming never changes, removes or re-approves a venue booking or equipment request")
    void confirmingLeavesBookingsAndEquipmentRequestsUntouched() {
        Event event = readyEvent(EventStatus.safety_approved);
        VenueBookingRecord booking = eventBookings.get(0);

        service.confirm(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        // State: the approved booking is exactly as it was.
        assertThat(booking.getStatus()).isEqualTo(VenueBookingStatus.approved);
        // Behaviour (mocks): confirming only reads bookings and equipment; it
        // never writes or deletes them.
        verify(bookings, never()).save(any());
        verify(bookings, never()).saveAll(any());
        verify(bookings, never()).delete(any());
        verify(equipment, never()).save(any());
        verify(equipment, never()).saveAll(any());
        verify(equipment, never()).delete(any());
    }

    @Test
    @DisplayName("EC08 AC29, EO04 AC23 (happy): re-confirming keeps the first confirmation date")
    void reConfirmingKeepsTheFirstConfirmationDate() {
        Event event = readyEvent(EventStatus.safety_approved);
        OffsetDateTime first = OffsetDateTime.parse("2026-11-01T09:00:00Z");
        event.setFirstConfirmedAt(first);

        service.confirm(COORDINATOR, event.getEventId());

        assertThat(event.getFirstConfirmedAt()).isEqualTo(first);
        assertThat(event.getConfirmedAt()).isAfter(first);
    }

    @Test
    @DisplayName("EC08 AC31, AC32 (happy): confirming notifies the organiser and the venue and technical staff involved")
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
    @DisplayName("EC08 AC32 (boundary): Technical Support is not told when the event has no approved equipment")
    void technicalSupportIsNotToldWhenTheEventHasNoApprovedEquipment() {
        Event event = readyEvent(EventStatus.safety_approved);

        service.confirm(COORDINATOR, event.getEventId());

        verify(notifications).createEventConfirmedNotifications(eq(List.of(VS_USER)), any(), any(), any());
    }

    @Test
    @DisplayName("EC08 AC25 (negative): an event cannot be confirmed before the Safety Officer approves")
    void anEventCannotBeConfirmedBeforeTheSafetyOfficerApproves() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessageContaining("after the Safety Officer approves");
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EC08 AC34, AC35 (conflict): confirming twice says it is already confirmed and notifies no one again")
    void confirmingTwiceSaysItIsAlreadyConfirmedAndNotifiesNoOneAgain() {
        Event event = readyEvent(EventStatus.confirmed);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessage("This event is already confirmed.");
        verifyNoInteractions(notifications, activity);
    }

    @Test
    @DisplayName("EC08 AC28, EC-NEW2 AC29 (boundary): confirming an event that has taken place says it is Completed")
    void confirmingAnEventThatHasTakenPlaceSaysItIsCompletedNotConfirmed() {
        Event event = readyEvent(EventStatus.confirmed);
        event.setEndDatetime(OffsetDateTime.parse("2026-12-30T12:00:00Z"));

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessage("This event has already taken place (Completed), so it cannot be confirmed again.");
        verifyNoInteractions(notifications, activity);
    }

    @Test
    @DisplayName("EC08 AC26, AC27 (conflict): prerequisites are re-checked at the moment of confirming, and nothing changes if one no longer holds")
    void prerequisitesAreReCheckedAtTheMomentOfConfirming() {
        Event event = readyEvent(EventStatus.safety_approved);
        when(availability.affected(eventBookings.get(0).getBookingId())).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(COORDINATOR, event.getEventId()))
                .isInstanceOf(EventNotReadyException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EC08 AC1, AC2 (cross-cutting): a coordinator not assigned to the event cannot confirm it")
    void aCoordinatorNotAssignedCannotConfirm() {
        Event event = readyEvent(EventStatus.safety_approved);

        assertThatThrownBy(() -> service.confirm(OTHER_COORDINATOR, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
    }

    // ---- Safety Officer decisions ---------------------------------------

    @Test
    @DisplayName("EC-NEW2 AC11, EO04 AC17 (happy): the Safety Officer's approval moves the event to Safety Approved and records when")
    void approvalMovesTheEventToSafetyApprovedAndRecordsWhen() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.APPROVE, null);

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
        assertThat(event.getSafetyApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("EC08 AC23, EC-NEW2 AC10 (happy): requesting changes needs feedback and shows it to the coordinator")
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
    @DisplayName("EC08 AC23 (failure): a safety rejection sends the event back to the coordinator rather than cancelling it")
    void aSafetyRejectionSendsTheEventBackToTheCoordinatorRatherThanCancellingIt() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.REJECT,
                "Capacity too tight for the layout.");

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_changes_requested);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.safety_rejected);
    }

    @Test
    @DisplayName("SO-C5 AC7, AC11, SO-C6 AC7, AC12 (happy): a decision's checklist items are kept on its timeline entry, and the event returns to the coordinator")
    void aDecisionsChecklistItemsAreKeptOnItsTimelineEntry() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.REJECT,
                "Exits blocked.", List.of("capacity_facilities", "safety_operations"));

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_changes_requested);
        assertThat(event.getSafetyFeedback()).isEqualTo("Exits blocked.");
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.safety_rejected);
        assertThat(entry.getValue().message()).isEqualTo("Exits blocked.");
        assertThat(entry.getValue().flaggedFields()).containsExactly("capacity_facilities", "safety_operations");
    }

    // ---- automatic return to Planning ------------------------------------

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"awaiting_safety_review", "safety_approved", "confirmed"})
    @DisplayName("EC08 AC21, AC22, EC-NEW2 AC15 (conflict): a venue becoming unavailable returns the event to Planning and tells the coordinator why")
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
    @DisplayName("EC-NEW2 AC16 (boundary): an event already in Planning is left as it is")
    void anEventAlreadyInPlanningIsLeftAsItIs() {
        Event event = readyEvent(EventStatus.pending);

        assertThat(service.returnToPlanning(event.getEventId(), VS_USER, "x")).isFalse();
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC-NEW2 AC29 (boundary): a completed event is never reopened")
    void aCompletedEventIsNeverReopened() {
        Event event = readyEvent(EventStatus.confirmed);
        event.setStartDatetime(OffsetDateTime.parse("2026-12-30T09:00:00Z"));
        event.setEndDatetime(OffsetDateTime.parse("2026-12-30T12:00:00Z"));

        assertThat(service.returnToPlanning(event.getEventId(), VS_USER, "x")).isFalse();
        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        verify(notifications, never()).createReturnedToPlanningNotification(any(), any(), any(), any());
    }

    // ---- edge cases (boundary, failure) ------------------------------------

    @Test
    @DisplayName("EC08 AC2 (negative): an event that does not exist is reported as not found by every action")
    void anUnknownEventIsNotFoundEverywhere() {
        UUID unknown = UUID.randomUUID();
        when(events.findById(unknown)).thenReturn(Optional.empty());
        when(events.findForUpdate(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readiness(COORDINATOR, unknown)).isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.sendForSafetyReview(COORDINATOR, unknown)).isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> service.recordSafetyDecision(OFFICER, unknown, EventLifecycleService.SafetyDecision.APPROVE, null))
                .isInstanceOf(EventNotFoundException.class);
        assertThat(service.returnToPlanning(unknown, VS_USER, "x")).isFalse();
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC08 AC1, AC2 (cross-cutting): a caller with no identity is never treated as the assigned coordinator")
    void aCallerWithNoIdentityIsRefused() {
        Event event = readyEvent(EventStatus.pending);

        assertThatThrownBy(() -> service.sendForSafetyReview(null, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
    }

    @Test
    @DisplayName("EC08 AC12 (boundary): equipment requirements that are only blank space count as none")
    void blankEquipmentRequirementsNeedNoEquipment() {
        Event event = readyEvent(EventStatus.pending);
        event.setEquipmentRequirements("   ");

        assertThat(item(service.readiness(COORDINATOR, event.getEventId()), "equipment").state()).isEqualTo("not_needed");
    }

    @Test
    @DisplayName("EC08 AC9, AC11 (negative): stated equipment needs with only a rejected request ask for a new request")
    void onlyARejectedEquipmentRequestStillBlocks() {
        Event event = readyEvent(EventStatus.pending);
        event.setEquipmentRequirements("Two wireless microphones");
        equipmentRequest(EquipmentRequestStatus.rejected);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.canSendForReview()).isFalse();
        assertThat(item(readiness, "equipment").detail())
                .isEqualTo("No equipment request has been approved. Submit a new request.");
    }

    @Test
    @DisplayName("EC08 AC31 (boundary): with no originating request there is no organiser to notify, and confirming still succeeds")
    void confirmingWithNoOriginatingRequestNotifiesNoOrganiser() {
        Event event = readyEvent(EventStatus.safety_approved);
        when(eventRequests.findByEventId(event.getEventId())).thenReturn(Optional.empty());

        service.confirm(COORDINATOR, event.getEventId());

        assertThat(event.getStatus()).isEqualTo(EventStatus.confirmed);
        verify(notifications, never()).createStatusChangeNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EC08 AC32 (boundary): with no Venue Staff accounts at all, no staff notification is sent")
    void noStaffAccountsMeansNoStaffNotification() {
        Event event = readyEvent(EventStatus.safety_approved);
        when(users.findByRole(UserRole.vs)).thenReturn(List.of());

        service.confirm(COORDINATOR, event.getEventId());

        verify(notifications, never()).createEventConfirmedNotifications(any(), any(), any(), any());
    }

    @Test
    @DisplayName("EC08 AC32 (boundary): rejected bookings and equipment do not decide who is told; approved ones do")
    void onlyApprovedArrangementsDecideWhichStaffAreTold() {
        Event event = readyEvent(EventStatus.safety_approved);
        booking(VenueBookingStatus.rejected);
        equipmentRequest(EquipmentRequestStatus.rejected);

        service.confirm(COORDINATOR, event.getEventId());

        // Venue Staff (an approved booking exists), not Technical Support (no approved equipment).
        verify(notifications).createEventConfirmedNotifications(eq(List.of(VS_USER)), any(), any(), any());
    }

    @Test
    @DisplayName("EC-NEW2 AC23 (conflict): a safety decision on an event no longer awaiting review is refused and changes nothing")
    void aSafetyDecisionOnAnEventNotAwaitingReviewIsRefused() {
        Event event = readyEvent(EventStatus.pending);

        assertThatThrownBy(() -> service.recordSafetyDecision(OFFICER, event.getEventId(),
                EventLifecycleService.SafetyDecision.APPROVE, null))
                .isInstanceOf(EventLifecycleException.class)
                .hasMessageContaining("this one is Planning");
        assertThat(event.getStatus()).isEqualTo(EventStatus.pending);
        verifyNoInteractions(activity, notifications);
    }

    @Test
    @DisplayName("EC08 AC23 (boundary): an approval note is kept, and one over 2000 characters is refused")
    void anApprovalNoteIsKeptButNotIfTooLong() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);
        assertThatThrownBy(() -> service.recordSafetyDecision(OFFICER, event.getEventId(),
                EventLifecycleService.SafetyDecision.APPROVE, "x".repeat(2001)))
                .isInstanceOf(EventLifecycleValidationException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.APPROVE,
                "  Exits checked.  ");

        assertThat(event.getStatus()).isEqualTo(EventStatus.safety_approved);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().message()).isEqualTo("Exits checked.");
    }

    @Test
    @DisplayName("EC08 AC22 (boundary): an event with no coordinator returns to Planning without anyone to notify")
    void anEventWithNoCoordinatorReturnsToPlanningQuietly() {
        Event returned = readyEvent(EventStatus.awaiting_safety_review);
        returned.setCoordinatorId(null);
        Event approved = readyEvent(EventStatus.awaiting_safety_review);
        approved.setCoordinatorId(null);

        assertThat(service.returnToPlanning(returned.getEventId(), VS_USER, "Venue flooded")).isTrue();
        service.recordSafetyDecision(OFFICER, approved.getEventId(), EventLifecycleService.SafetyDecision.APPROVE, null);

        assertThat(returned.getStatus()).isEqualTo(EventStatus.pending);
        assertThat(approved.getStatus()).isEqualTo(EventStatus.safety_approved);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EC08 AC19 (negative): a missing reason (not just a blank one) is refused before anything is loaded")
    void aMissingReasonIsRefused() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.withdrawFromSafetyReview(COORDINATOR, event.getEventId(), null))
                .isInstanceOf(EventLifecycleValidationException.class);
        verify(events, never()).findForUpdate(any());
    }

    @Test
    @DisplayName("EC-NEW2 AC14 (boundary): an event with no end time is never flagged as Date passed")
    void anEventWithNoEndIsNeverFlagged() {
        Event event = readyEvent(EventStatus.pending);
        event.setEndDatetime(null);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(readiness.datePassed()).isFalse();
        assertThat(readiness.canSendForReview()).isTrue();
    }

    @Test
    @DisplayName("EC08 AC9, AC11 (happy): stated equipment needs with an approved request are complete")
    void statedEquipmentNeedsWithAnApprovedRequestAreComplete() {
        Event event = readyEvent(EventStatus.pending);
        event.setEquipmentRequirements("Two wireless microphones");
        equipmentRequest(EquipmentRequestStatus.approved);

        EventReadinessDto readiness = service.readiness(COORDINATOR, event.getEventId());

        assertThat(item(readiness, "equipment").state()).isEqualTo("complete");
        assertThat(readiness.canSendForReview()).isTrue();
    }

    @Test
    @DisplayName("EC08 AC23 (boundary): a blank approval note is treated as no note")
    void aBlankApprovalNoteIsNoNote() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);

        service.recordSafetyDecision(OFFICER, event.getEventId(), EventLifecycleService.SafetyDecision.APPROVE, "   ");

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().message()).isNull();
    }

    @Test
    @DisplayName("ECL-C7 AC17, AC19 (conflict): after a reassignment, notifications go to the new coordinator and never the previous one")
    void notificationsFollowTheEventsCurrentCoordinator() {
        Event event = readyEvent(EventStatus.awaiting_safety_review);
        // The Lead reassigned the event: the event row now names the new coordinator.
        event.setCoordinatorId(OTHER_COORDINATOR);

        service.returnToPlanning(event.getEventId(), VS_USER, "A venue for this event became unavailable: flooding");

        verify(notifications).createReturnedToPlanningNotification(eq(OTHER_COORDINATOR), eq(event.getEventId()),
                any(), any());
        verify(notifications, never()).createReturnedToPlanningNotification(eq(COORDINATOR), any(), any(), any());
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
