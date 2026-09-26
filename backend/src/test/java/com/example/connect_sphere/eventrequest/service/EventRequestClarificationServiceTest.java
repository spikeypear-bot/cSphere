package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EC01 (request clarification), EO26 (respond and resubmit) and EC02
 * (review outcome) business rules, with every repository mocked. Each test
 * is named after the acceptance criterion it checks, and every expected value
 * comes from the AC text, not from running the code (IS212 Week 6).
 *
 * <p>The database-level guarantees (append-only timeline, audience filtering,
 * the real filter chain) are in EventRequestReviewFlowTest.
 */
class EventRequestClarificationServiceTest {

    @Mock private EventRequestRepository repository;
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ActivityService activityService;

    private EventRequestService service;

    private static final String ORG = "Acme Pte Ltd";
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR = UUID.randomUUID();
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID SECOND_ORGANISER = UUID.randomUUID();

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
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByRoleAndOrganisation(UserRole.eo, ORG))
                .thenReturn(List.of(user(ORGANISER), user(SECOND_ORGANISER)));
    }

    // ---- EC01 ---------------------------------------------------------

    @Test
    void clarificationMovesASubmittedRequestToClarificationRequired() {
        EventRequest request = stored(EventRequestStatus.pending);

        EventRequestDto result = service.requestClarification(
                COORDINATOR, request.getRequestId(), "  Please confirm the end time.  ", List.of("endDatetime"));

        assertThat(result.status()).isEqualTo(EventRequestStatus.clarification_required);
    }

    @Test
    void clarificationRecordsMessageFlagsCoordinatorAndStatusChangeOnTheTimeline() {
        EventRequest request = stored(EventRequestStatus.pending);

        service.requestClarification(COORDINATOR, request.getRequestId(), "Please confirm the end time.",
                List.of("endDatetime", "expectedAttendance"));

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.clarification_requested);
        assertThat(entry.getValue().actorUserId()).isEqualTo(COORDINATOR);
        assertThat(entry.getValue().message()).isEqualTo("Please confirm the end time.");
        assertThat(entry.getValue().flaggedFields()).containsExactly("endDatetime", "expectedAttendance");
        assertThat(entry.getValue().fromStatus()).isEqualTo("pending");
        assertThat(entry.getValue().toStatus()).isEqualTo("clarification_required");
    }

    @Test
    void everyOrganiserOfTheOwningOrganisationIsNotifiedWithTheMessage() {
        EventRequest request = stored(EventRequestStatus.pending);

        service.requestClarification(COORDINATOR, request.getRequestId(), "Please confirm the end time.", null);

        verify(notificationService).createClarificationRequestedNotifications(
                List.of(ORGANISER, SECOND_ORGANISER), request.getRequestId(), "Q1 Town Hall",
                "Please confirm the end time.");
    }

    @Test
    void clarificationDoesNotChangeAnyDetailTheOrganiserSubmitted() {
        EventRequest request = stored(EventRequestStatus.pending);

        service.requestClarification(COORDINATOR, request.getRequestId(), "Is 150 attendees right?",
                List.of("expectedAttendance"));

        assertThat(request.getEventName()).isEqualTo("Q1 Town Hall");
        assertThat(request.getPurpose()).isEqualTo("All-hands update");
        assertThat(request.getExpectedAttendance()).isEqualTo(150);
        assertThat(request.getVenueRequirements()).isEqualTo("Theatre-style seating for 150");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t "})
    void blankOrWhitespaceMessageIsRejectedAndNothingChanges(String message) {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.requestClarification(COORDINATOR, request.getRequestId(), message, null))
                .isInstanceOf(InvalidMessageException.class);

        assertNothingChanged(request, EventRequestStatus.pending);
    }

    @Test
    void aMessageOfExactly2000CharactersIsAccepted() {
        EventRequest request = stored(EventRequestStatus.pending);

        service.requestClarification(COORDINATOR, request.getRequestId(), "x".repeat(2000), null);

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.clarification_required);
    }

    @Test
    void aMessageOf2001CharactersIsRejectedAndNothingChanges() {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.requestClarification(
                COORDINATOR, request.getRequestId(), "x".repeat(2001), null))
                .isInstanceOf(InvalidMessageException.class);

        assertNothingChanged(request, EventRequestStatus.pending);
    }

    @Test
    void flaggingAFieldThatDoesNotExistIsRejected() {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.requestClarification(
                COORDINATOR, request.getRequestId(), "Check this", List.of("budget")))
                .isInstanceOf(InvalidMessageException.class);

        assertNothingChanged(request, EventRequestStatus.pending);
    }

    @Test
    void aCoordinatorNotAssignedToTheRequestCannotRequestClarification() {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.requestClarification(
                OTHER_COORDINATOR, request.getRequestId(), "Please confirm", null))
                .isInstanceOf(NotAssignedCoordinatorException.class);

        assertNothingChanged(request, EventRequestStatus.pending);
    }

    @Test
    void aSecondClarificationCannotBeSentWhileOneIsStillOpen() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        assertThatThrownBy(() -> service.requestClarification(
                COORDINATOR, request.getRequestId(), "And another thing", null))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been requested");

        assertNothingChanged(request, EventRequestStatus.clarification_required);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"draft", "approved", "rejected", "cancelled"})
    void clarificationIsOnlyPossibleOnASubmittedRequest(EventRequestStatus status) {
        EventRequest request = stored(status);

        assertThatThrownBy(() -> service.requestClarification(
                COORDINATOR, request.getRequestId(), "Please confirm", null))
                .isInstanceOf(EventRequestStateException.class);

        assertNothingChanged(request, status);
    }

    @Test
    void aRequestAwaitingClarificationCannotBeApproved() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        assertThatThrownBy(() -> service.approve(COORDINATOR, request.getRequestId()))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("waiting for the organiser");

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.clarification_required);
        verify(eventRepository, never()).save(any(Event.class));
        verifyNoInteractions(activityService);
    }

    @Test
    void aRequestAwaitingClarificationCanStillBeRejectedWithAReason() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        service.reject(COORDINATOR, request.getRequestId(), "No response from the organiser after two weeks");

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.rejected);
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().fromStatus()).isEqualTo("clarification_required");
        assertThat(entry.getValue().message()).isEqualTo("No response from the organiser after two weeks");
    }

    // ---- EO26 ---------------------------------------------------------

    @Test
    void resubmittingReturnsTheRequestToSubmittedWithTheSameCoordinator() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        EventRequestDto result = service.resubmit(ORG, ORGANISER, request.getRequestId(), "End time is 5pm.");

        assertThat(result.status()).isEqualTo(EventRequestStatus.pending);
        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR);
    }

    @Test
    void resubmittingRecordsTheResponseAndNotifiesTheAssignedCoordinator() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        service.resubmit(ORG, ORGANISER, request.getRequestId(), "  End time is 5pm.  ");

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.clarification_responded);
        assertThat(entry.getValue().actorUserId()).isEqualTo(ORGANISER);
        assertThat(entry.getValue().message()).isEqualTo("End time is 5pm.");
        assertThat(entry.getValue().toStatus()).isEqualTo("pending");
        verify(notificationService).createClarificationRespondedNotification(
                COORDINATOR, request.getRequestId(), "Q1 Town Hall", "End time is 5pm.");
    }

    @Test
    void resubmittingWithoutAResponseIsRejectedAndNothingChanges() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "   "))
                .isInstanceOf(InvalidMessageException.class);

        assertNothingChanged(request, EventRequestStatus.clarification_required);
    }

    @Test
    void resubmittingAppliesTheSameCompletenessCheckAsTheFirstSubmission() {
        EventRequest request = stored(EventRequestStatus.clarification_required);
        request.setPurpose("   ");

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "Updated"))
                .isInstanceOf(IncompleteEventRequestException.class)
                .satisfies(ex -> assertThat(((IncompleteEventRequestException) ex).getMissingFields())
                        .containsExactly("purpose"));

        assertNothingChanged(request, EventRequestStatus.clarification_required);
    }

    @Test
    void resubmittingAppliesTheSameDateRuleAsTheFirstSubmission() {
        EventRequest request = stored(EventRequestStatus.clarification_required);
        request.setEndDatetime(request.getStartDatetime().minusMinutes(1));

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "Updated"))
                .isInstanceOf(InvalidEventRequestScheduleException.class);

        assertNothingChanged(request, EventRequestStatus.clarification_required);
    }

    @Test
    void onlyARequestWaitingForClarificationCanBeResubmitted() {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "Updated"))
                .isInstanceOf(EventRequestStateException.class);

        assertNothingChanged(request, EventRequestStatus.pending);
    }

    @Test
    void anOrganiserOfAnotherOrganisationCannotResubmit() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        assertThatThrownBy(() -> service.resubmit("Globex Holdings", UUID.randomUUID(),
                request.getRequestId(), "Updated"))
                .isInstanceOf(EventRequestNotFoundException.class);

        assertNothingChanged(request, EventRequestStatus.clarification_required);
    }

    @Test
    void theOrganiserCanEditARequestWaitingForClarificationWithoutChangingItsStatus() {
        EventRequest request = stored(EventRequestStatus.clarification_required);

        service.updateDraft(ORG, request.getRequestId(), new SaveEventRequestRequest(
                "Q1 Town Hall", "All-hands update", null, request.getStartDatetime(), request.getEndDatetime(),
                120, "Theatre-style seating for 120", null, List.of(AccessibilityFeature.none), null));

        assertThat(request.getExpectedAttendance()).isEqualTo(120);
        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.clarification_required);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"pending", "approved", "rejected", "cancelled"})
    void aRequestThatIsNeitherDraftNorWaitingForClarificationCannotBeEdited(EventRequestStatus status) {
        EventRequest request = stored(status);

        assertThatThrownBy(() -> service.updateDraft(ORG, request.getRequestId(),
                new SaveEventRequestRequest("Renamed", null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(EventRequestNotEditableException.class);

        assertThat(request.getEventName()).isEqualTo("Q1 Town Hall");
    }

    // ---- EC02 ---------------------------------------------------------

    @Test
    void approvalRechecksMandatoryFieldsAndListsWhatIsMissing() {
        EventRequest request = stored(EventRequestStatus.pending);
        request.setVenueRequirements(null);

        assertThatThrownBy(() -> service.approve(COORDINATOR, request.getRequestId()))
                .isInstanceOf(IncompleteEventRequestException.class)
                .satisfies(ex -> assertThat(((IncompleteEventRequestException) ex).getMissingFields())
                        .containsExactly("venueRequirements"));

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void approvalRecordsTheOutcomeAgainstTheNewEvent() {
        EventRequest request = stored(EventRequestStatus.pending);

        EventRequestDto result = service.approve(COORDINATOR, request.getRequestId());

        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activityService).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.approved);
        assertThat(entry.getValue().eventId()).isEqualTo(result.eventId()).isNotNull();
        assertThat(entry.getValue().actorUserId()).isEqualTo(COORDINATOR);
    }

    @Test
    void theReviewScreenIsOnlyAvailableToTheAssignedCoordinator() {
        EventRequest request = stored(EventRequestStatus.pending);

        assertThatThrownBy(() -> service.getForReview(OTHER_COORDINATOR, request.getRequestId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        verify(activityService, never()).timeline(any(), any());
    }

    @Test
    void theReviewScreenReportsWhatWouldBlockApproval() {
        EventRequest request = stored(EventRequestStatus.pending);
        request.setExpectedAttendance(null);
        request.setEndDatetime(request.getStartDatetime().minusHours(1));

        var review = service.getForReview(COORDINATOR, request.getRequestId());

        assertThat(review.missingFields()).containsExactly("expectedAttendance");
        assertThat(review.scheduleValid()).isFalse();
    }

    // ---- helpers ------------------------------------------------------

    /** A complete request of ORG, assigned to COORDINATOR, in {@code status}. */
    private EventRequest stored(EventRequestStatus status) {
        EventRequest entity = new EventRequest();
        entity.setRequestId(UUID.randomUUID());
        entity.setRequestType('C');
        entity.setOrganisation(ORG);
        entity.setCreatedBy(ORGANISER);
        entity.setCoordinatorId(COORDINATOR);
        entity.setStatus(status);
        entity.setEventName("Q1 Town Hall");
        entity.setPurpose("All-hands update");
        entity.setStartDatetime(OffsetDateTime.now().plusDays(30));
        entity.setEndDatetime(OffsetDateTime.now().plusDays(30).plusHours(2));
        entity.setExpectedAttendance(150);
        entity.setVenueRequirements("Theatre-style seating for 150");
        entity.setAccessibilityNeeds(List.of(AccessibilityFeature.none));
        OffsetDateTime created = OffsetDateTime.now().minusDays(1);
        entity.setCreatedAt(created);
        entity.setUpdatedAt(created);
        when(repository.findById(entity.getRequestId())).thenReturn(Optional.of(entity));
        when(repository.findForUpdate(entity.getRequestId())).thenReturn(Optional.of(entity));
        return entity;
    }

    /** EC01/EO26: a failed action "does not change the status or create a
     * history record", and nobody is notified about it. */
    private void assertNothingChanged(EventRequest request, EventRequestStatus expectedStatus) {
        assertThat(request.getStatus()).isEqualTo(expectedStatus);
        verify(repository, never()).save(any());
        verifyNoInteractions(activityService);
        verify(notificationService, never()).createClarificationRequestedNotifications(anyList(), any(), any(), any());
        verify(notificationService, never()).createClarificationRespondedNotification(any(), any(), any(), anyString());
    }

    private static User user(UUID id) {
        User user = new User();
        user.setUserId(id);
        user.setUsername("eo-" + id.toString().substring(0, 4));
        user.setRole(UserRole.eo);
        user.setOrganisation(ORG);
        return user;
    }
}
