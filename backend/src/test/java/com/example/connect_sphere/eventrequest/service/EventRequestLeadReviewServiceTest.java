package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.EventRequestReviewDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * ECL-C3 and ELC-C6: the Event Coordinator Lead's review of an unassigned
 * request and its assignment to an Event Coordinator, with every repository
 * mocked. Each test is named after the acceptance criterion it checks, and
 * expected values come from the criteria, not from the code.
 */
@Tag("unit")
class EventRequestLeadReviewServiceTest {

    @Mock private EventRequestRepository repository;
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ActivityService activityService;

    private EventRequestService service;

    private static final String ORG = "Acme Pte Ltd";
    private static final UUID LEAD = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID SECOND_ORGANISER = UUID.randomUUID();
    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2027-03-01T09:00:00+08:00");
    private static final OffsetDateTime ENDS = OffsetDateTime.parse("2027-03-01T11:00:00+08:00");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        EventRequestMapper mapper = e -> new EventRequestDto(
                e.getRequestId(), e.getRequestType(), e.getEventId(), e.getEventName(), e.getPurpose(),
                e.getDescription(), e.getStartDatetime(), e.getEndDatetime(), e.getExpectedAttendance(),
                e.getVenueRequirements(), e.getEquipmentRequirements(), e.getAccessibilityNeeds(),
                e.getRegistrationNeeds(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt(), e.getOrganisation(),
                e.getCoordinatorId(), e.getRejectionReason(), null);
        service = new EventRequestService(
                repository, mapper, eventRepository, userRepository, notificationService, activityService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByRoleAndOrganisation(UserRole.eo, ORG))
                .thenReturn(List.of(organiser(ORGANISER), organiser(SECOND_ORGANISER)));
    }

    // ---- Viewing the request ------------------------------------------

    @Test
    void theReviewShowsTheRequestAsTheOrganiserSubmittedIt() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        EventRequestDto shown = service.getForLeadReview(request.getRequestId()).request();

        assertThat(shown.requestId()).isEqualTo(request.getRequestId());
        assertThat(shown.eventName()).isEqualTo("Q1 Town Hall");
        assertThat(shown.organisation()).isEqualTo(ORG);
        assertThat(shown.purpose()).isEqualTo("All-hands update");
        assertThat(shown.description()).isEqualTo("Quarterly results and Q&A");
        assertThat(shown.startDatetime()).isEqualTo(STARTS);
        assertThat(shown.endDatetime()).isEqualTo(ENDS);
        assertThat(shown.expectedAttendance()).isEqualTo(150);
        assertThat(shown.venueRequirements()).isEqualTo("Theatre-style seating for 150");
        assertThat(shown.equipmentRequirements()).isEqualTo("Two wireless microphones");
        assertThat(shown.accessibilityNeeds()).containsExactly(AccessibilityFeature.none);
        assertThat(shown.registrationNeeds()).isTrue();
        assertThat(shown.status()).isEqualTo(EventRequestStatus.pending);
    }

    @Test
    void theReviewIncludesTheRequestsHistory() {
        EventRequest request = stored(EventRequestStatus.pending, null);
        ActivityDto reply = new ActivityDto(UUID.randomUUID(), "clarification_responded", "eo1", "eo",
                "End time is 5pm.", List.of(), "clarification_required", "pending", OffsetDateTime.now(), null, null);
        when(activityService.timeline(request.getRequestId(), UserRole.ec)).thenReturn(List.of(reply));

        EventRequestReviewDto review = service.getForLeadReview(request.getRequestId());

        assertThat(review.timeline()).containsExactly(reply);
    }

    @Test
    void aDraftIsReportedAsNotFound() {
        EventRequest draft = stored(EventRequestStatus.draft, null);

        assertThatThrownBy(() -> service.getForLeadReview(draft.getRequestId()))
                .isInstanceOf(EventRequestNotFoundException.class);
    }

    @Test
    void aRequestThatDoesNotExistIsReportedAsNotFound() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForLeadReview(missing))
                .isInstanceOf(EventRequestNotFoundException.class);
    }

    @Test
    void aRequestThatHasSinceBeenAssignedStillOpensAndShowsItsCoordinator() {
        EventRequest request = stored(EventRequestStatus.pending, COORDINATOR);

        EventRequestDto shown = service.getForLeadReview(request.getRequestId()).request();

        assertThat(shown.coordinatorId()).isEqualTo(COORDINATOR);
        assertThat(shown.status()).isEqualTo(EventRequestStatus.pending);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"approved", "rejected", "cancelled"})
    void aRequestThatHasSinceBeenDecidedStillOpensAndShowsItsStatus(EventRequestStatus status) {
        EventRequest request = stored(status, COORDINATOR);

        assertThat(service.getForLeadReview(request.getRequestId()).request().status()).isEqualTo(status);
    }

    @Test
    void openingTheReviewChangesNothingAndNotifiesNoOne() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.getForLeadReview(request.getRequestId());

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        assertThat(request.getCoordinatorId()).isNull();
        verify(repository, never()).save(any());
        verify(activityService, never()).record(any());
        verifyNoInteractions(notificationService, eventRepository, userRepository);
    }

    // ---- Rejecting ----------------------------------------------------

    @Test
    void rejectingAnUnassignedRequestMakesItRejectedWithTheReasonAndAssignsNoOne() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        EventRequestDto result = service.rejectUnassigned(LEAD, request.getRequestId(), "Not a corporate event");

        assertThat(result.status()).isEqualTo(EventRequestStatus.rejected);
        assertThat(result.rejectionReason()).isEqualTo("Not a corporate event");
        assertThat(result.coordinatorId()).isNull();
    }

    @Test
    void rejectingNotifiesTheOrganiserWithTheReason() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.rejectUnassigned(LEAD, request.getRequestId(), "Not a corporate event");

        verify(notificationService).createStatusChangeNotification(
                ORGANISER, request.getRequestId(), null, "Q1 Town Hall", "rejected", "Not a corporate event");
    }

    @Test
    void rejectingIsRecordedOnTheTimelineWithTheLeadAsTheActor() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.rejectUnassigned(LEAD, request.getRequestId(), "Not a corporate event");

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.rejected);
        assertThat(entry.getValue().actorUserId()).isEqualTo(LEAD);
        assertThat(entry.getValue().message()).isEqualTo("Not a corporate event");
        assertThat(entry.getValue().fromStatus()).isEqualTo("pending");
        assertThat(entry.getValue().toStatus()).isEqualTo("rejected");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectingWithoutAReasonIsBlockedAndNothingChanges(String reason) {
        EventRequest request = stored(EventRequestStatus.pending, null);

        assertThatThrownBy(() -> service.rejectUnassigned(LEAD, request.getRequestId(), reason))
                .isInstanceOf(MissingRejectionReasonException.class);
        assertNothingChanged(request, EventRequestStatus.pending, null);
    }

    @Test
    void aRequestAssignedSinceThePageLoadedCannotBeRejectedByTheLead() {
        EventRequest request = stored(EventRequestStatus.pending, COORDINATOR);

        assertThatThrownBy(() -> service.rejectUnassigned(LEAD, request.getRequestId(), "Not a corporate event"))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been assigned");
        assertNothingChanged(request, EventRequestStatus.pending, COORDINATOR);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class,
            names = {"clarification_required", "approved", "rejected", "cancelled"})
    void aRequestThatIsNoLongerSubmittedCannotBeRejectedByTheLead(EventRequestStatus status) {
        EventRequest request = stored(status, null);

        assertThatThrownBy(() -> service.rejectUnassigned(LEAD, request.getRequestId(), "Not a corporate event"))
                .isInstanceOf(EventRequestStateException.class);
        assertNothingChanged(request, status, null);
    }

    @Test
    void aDraftCannotBeRejectedAndIsReportedAsNotFound() {
        EventRequest draft = stored(EventRequestStatus.draft, null);

        assertThatThrownBy(() -> service.rejectUnassigned(LEAD, draft.getRequestId(), "Not a corporate event"))
                .isInstanceOf(EventRequestNotFoundException.class);
        assertNothingChanged(draft, EventRequestStatus.draft, null);
    }

    // ---- Asking for clarification ----------------------------------

    @Test
    void askingForClarificationMakesTheRequestClarificationRequiredAndAssignsNoOne() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        EventRequestDto result = service.requestClarificationUnassigned(
                LEAD, request.getRequestId(), "  Who is the event for?  ");

        assertThat(result.status()).isEqualTo(EventRequestStatus.clarification_required);
        assertThat(result.coordinatorId()).isNull();
    }

    @Test
    void askingForClarificationNotifiesEveryOrganiserOfTheOrganisationWithTheMessage() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.requestClarificationUnassigned(LEAD, request.getRequestId(), "Who is the event for?");

        verify(notificationService).createClarificationRequestedNotifications(
                List.of(ORGANISER, SECOND_ORGANISER), request.getRequestId(), "Q1 Town Hall",
                "Who is the event for?");
    }

    @Test
    void askingForClarificationIsRecordedOnTheTimelineWithTheLeadAsTheActor() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.requestClarificationUnassigned(LEAD, request.getRequestId(), "Who is the event for?");

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.clarification_requested);
        assertThat(entry.getValue().actorUserId()).isEqualTo(LEAD);
        assertThat(entry.getValue().message()).isEqualTo("Who is the event for?");
        assertThat(entry.getValue().fromStatus()).isEqualTo("pending");
        assertThat(entry.getValue().toStatus()).isEqualTo("clarification_required");
    }

    @Test
    void askingForClarificationDoesNotChangeAnyDetailTheOrganiserSubmitted() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        service.requestClarificationUnassigned(LEAD, request.getRequestId(), "Who is the event for?");

        assertThat(request.getEventName()).isEqualTo("Q1 Town Hall");
        assertThat(request.getStartDatetime()).isEqualTo(STARTS);
        assertThat(request.getEndDatetime()).isEqualTo(ENDS);
        assertThat(request.getExpectedAttendance()).isEqualTo(150);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void askingForClarificationWithoutAMessageIsBlockedAndNothingChanges(String message) {
        EventRequest request = stored(EventRequestStatus.pending, null);

        assertThatThrownBy(() -> service.requestClarificationUnassigned(LEAD, request.getRequestId(), message))
                .isInstanceOf(InvalidMessageException.class);
        assertNothingChanged(request, EventRequestStatus.pending, null);
    }

    @Test
    void aRequestAssignedSinceThePageLoadedCannotBeSentBackByTheLead() {
        EventRequest request = stored(EventRequestStatus.pending, COORDINATOR);

        assertThatThrownBy(
                () -> service.requestClarificationUnassigned(LEAD, request.getRequestId(), "Who is the event for?"))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been assigned");
        assertNothingChanged(request, EventRequestStatus.pending, COORDINATOR);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class,
            names = {"clarification_required", "approved", "rejected", "cancelled"})
    void aRequestThatIsNoLongerSubmittedCannotBeSentBackByTheLead(EventRequestStatus status) {
        EventRequest request = stored(status, null);

        assertThatThrownBy(
                () -> service.requestClarificationUnassigned(LEAD, request.getRequestId(), "Who is the event for?"))
                .isInstanceOf(EventRequestStateException.class);
        assertNothingChanged(request, status, null);
    }

    @Test
    void whenTheOrganiserResubmitsTheRequestIsSubmittedAgainWithNoCoordinator() {
        EventRequest request = stored(EventRequestStatus.clarification_required, null);

        EventRequestDto result = service.resubmit(ORG, ORGANISER, request.getRequestId(), "It is for our staff.");

        // Submitted with no coordinator is exactly what the unassigned list shows.
        assertThat(result.status()).isEqualTo(EventRequestStatus.pending);
        assertThat(result.coordinatorId()).isNull();
        verify(notificationService, never()).createClarificationRespondedNotification(any(), any(), any(), any());
    }

    // ---- Assigning (ELC-C6) ------------------------------------------

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"pending", "clarification_required"})
    void assigningGivesTheRequestItsCoordinatorAndKeepsItsStatus(EventRequestStatus status) {
        EventRequest request = stored(status, null);
        coordinator("ec1");

        EventRequestDto result = service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR);

        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR);
        assertThat(result.status()).isEqualTo(status);
    }

    @Test
    void assigningIsRecordedOnTheTimelineWithTheLeadAsTheActorAndTheCoordinatorsName() {
        EventRequest request = stored(EventRequestStatus.pending, null);
        coordinator("ec1");

        service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR);

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.coordinator_assigned);
        assertThat(entry.getValue().actorUserId()).isEqualTo(LEAD);
        assertThat(entry.getValue().message()).contains("ec1");
        // The status did not move, so the entry claims no status change.
        assertThat(entry.getValue().fromStatus()).isNull();
        assertThat(entry.getValue().toStatus()).isNull();
    }

    @Test
    void assigningDoesNotApproveTheRequestCreateAnEventOrChangeAnyOtherDetail() {
        EventRequest request = stored(EventRequestStatus.pending, null);
        coordinator("ec1");

        EventRequestDto result = service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR);

        assertThat(result.eventId()).isNull();
        verifyNoInteractions(eventRepository);
        assertThat(result.eventName()).isEqualTo("Q1 Town Hall");
        assertThat(result.organisation()).isEqualTo(ORG);
        assertThat(result.purpose()).isEqualTo("All-hands update");
        assertThat(result.description()).isEqualTo("Quarterly results and Q&A");
        assertThat(result.startDatetime()).isEqualTo(STARTS);
        assertThat(result.endDatetime()).isEqualTo(ENDS);
        assertThat(result.expectedAttendance()).isEqualTo(150);
        assertThat(result.venueRequirements()).isEqualTo("Theatre-style seating for 150");
        assertThat(result.equipmentRequirements()).isEqualTo("Two wireless microphones");
        assertThat(result.accessibilityNeeds()).containsExactly(AccessibilityFeature.none);
        assertThat(result.registrationNeeds()).isTrue();
        assertThat(result.rejectionReason()).isNull();
        assertThat(request.getCreatedBy()).isEqualTo(ORGANISER);
    }

    @Test
    void assigningTellsTheOrganiserWhoTheirCoordinatorIsAndNotifiesNobodyElse() {
        EventRequest request = stored(EventRequestStatus.pending, null);
        coordinator("ec1");

        service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR);

        verify(notificationService).createCoordinatorAssignmentNotification(
                ORGANISER, request.getRequestId(), null, "Q1 Town Hall", "ec1", "ec1@connectsphere.test", false);
        // Telling the coordinator is a separate story (EC-NEW4).
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void assigningWithoutChoosingACoordinatorIsBlockedAndNothingChanges() {
        EventRequest request = stored(EventRequestStatus.pending, null);

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, request.getRequestId(), null))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessage("A coordinator must be specified");
        assertNothingChanged(request, EventRequestStatus.pending, null);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = "ec")
    void aUserWhoIsNotAnEventCoordinatorCannotBeAssignedAndNothingChanges(UserRole otherRole) {
        EventRequest request = stored(EventRequestStatus.pending, null);
        coordinator("someone").setRole(otherRole);

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessageContaining("is not an Event Coordinator");
        assertNothingChanged(request, EventRequestStatus.pending, null);
    }

    @Test
    void aUserThatDoesNotExistCannotBeAssignedAndNothingChanges() {
        EventRequest request = stored(EventRequestStatus.pending, null);
        when(userRepository.findById(COORDINATOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessageContaining("is not an Event Coordinator");
        assertNothingChanged(request, EventRequestStatus.pending, null);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"pending", "clarification_required"})
    void aRequestAssignedSinceThePageLoadedCannotBeAssignedAgain(EventRequestStatus status) {
        UUID alreadyAssigned = UUID.randomUUID();
        EventRequest request = stored(status, alreadyAssigned);
        coordinator("ec1");

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been assigned");
        assertNothingChanged(request, status, alreadyAssigned);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"approved", "rejected", "cancelled"})
    void aRequestDecidedSinceThePageLoadedCannotBeAssigned(EventRequestStatus status) {
        EventRequest request = stored(status, null);
        coordinator("ec1");

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("no longer waiting for assignment");
        assertNothingChanged(request, status, null);
    }

    @Test
    void aDraftCannotBeAssignedAndIsReportedAsNotFound() {
        EventRequest draft = stored(EventRequestStatus.draft, null);
        coordinator("ec1");

        assertThatThrownBy(() -> service.assignCoordinator(LEAD, draft.getRequestId(), COORDINATOR))
                .isInstanceOf(EventRequestNotFoundException.class);
        assertNothingChanged(draft, EventRequestStatus.draft, null);
    }

    @Test
    void whenTheOrganiserResubmitsARequestAssignedWhileItWaitedItGoesToThatCoordinator() {
        EventRequest request = stored(EventRequestStatus.clarification_required, null);
        coordinator("ec1");
        service.assignCoordinator(LEAD, request.getRequestId(), COORDINATOR);

        EventRequestDto result = service.resubmit(ORG, ORGANISER, request.getRequestId(), "It is for our staff.");

        // Submitted and still theirs: in their review queue, not the unassigned list.
        assertThat(result.status()).isEqualTo(EventRequestStatus.pending);
        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR);
        verify(notificationService).createClarificationRespondedNotification(
                COORDINATOR, request.getRequestId(), "Q1 Town Hall", "It is for our staff.");
    }

    // ---- Fixtures -----------------------------------------------------

    /** A refused action leaves the request as it was, with no timeline
     * entry and nobody notified. */
    private void assertNothingChanged(EventRequest request, EventRequestStatus status, UUID coordinatorId) {
        assertThat(request.getStatus()).isEqualTo(status);
        assertThat(request.getCoordinatorId()).isEqualTo(coordinatorId);
        assertThat(request.getRejectionReason()).isNull();
        verify(repository, never()).save(any());
        verify(activityService, never()).record(any());
        verifyNoInteractions(notificationService);
    }

    /** A complete request of ORG in {@code status}, assigned to
     * {@code coordinatorId} (null for nobody). */
    private EventRequest stored(EventRequestStatus status, UUID coordinatorId) {
        EventRequest entity = new EventRequest();
        entity.setRequestId(UUID.randomUUID());
        entity.setRequestType('C');
        entity.setOrganisation(ORG);
        entity.setCreatedBy(ORGANISER);
        entity.setCoordinatorId(coordinatorId);
        entity.setStatus(status);
        entity.setEventName("Q1 Town Hall");
        entity.setPurpose("All-hands update");
        entity.setDescription("Quarterly results and Q&A");
        entity.setStartDatetime(STARTS);
        entity.setEndDatetime(ENDS);
        entity.setExpectedAttendance(150);
        entity.setVenueRequirements("Theatre-style seating for 150");
        entity.setEquipmentRequirements("Two wireless microphones");
        entity.setAccessibilityNeeds(List.of(AccessibilityFeature.none));
        entity.setRegistrationNeeds(true);
        OffsetDateTime submitted = OffsetDateTime.now().minusDays(1);
        entity.setCreatedAt(submitted);
        entity.setUpdatedAt(submitted);
        when(repository.findById(entity.getRequestId())).thenReturn(Optional.of(entity));
        when(repository.findForUpdate(entity.getRequestId())).thenReturn(Optional.of(entity));
        return entity;
    }

    /** Makes COORDINATOR a real Event Coordinator account the Lead can pick. */
    private User coordinator(String username) {
        User user = new User();
        user.setUserId(COORDINATOR);
        user.setUsername(username);
        user.setEmail(username + "@connectsphere.test");
        user.setRole(UserRole.ec);
        when(userRepository.findById(COORDINATOR)).thenReturn(Optional.of(user));
        return user;
    }

    private static User organiser(UUID id) {
        User user = new User();
        user.setUserId(id);
        user.setRole(UserRole.eo);
        user.setOrganisation(ORG);
        return user;
    }
}
