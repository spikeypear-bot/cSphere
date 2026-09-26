package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
 * Unit tests for EventRequestService — EO01/EO02's own suite, plus (as of
 * the EO09/EO19 slice) the minimal Coordinator-side transitions those two
 * stories' notifications fire from. These don't need a database — every
 * repository is mocked — so they run fast and are the first line of
 * defence; a Postgres-backed integration test is a follow-up (see
 * docs/decision-log.md).
 */
class EventRequestServiceTest {

    @Mock
    private EventRequestRepository repository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ActivityService activityService;

    private EventRequestService service;

    private static final String ORG = "Acme Conferences";
    private static final UUID CREATED_BY = UUID.randomUUID();
    private static final UUID COORDINATOR_ID = UUID.randomUUID();
    private static final UUID OTHER_COORDINATOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Real MapStruct-generated mapper isn't available without the annotation
        // processor running; a hand-written equivalent is enough to exercise the
        // service's own logic, which is what these tests are about.
        EventRequestMapper mapper = new EventRequestMapper() {
            @Override
            public EventRequestDto toDto(EventRequest entity) {
                return new EventRequestDto(
                        entity.getRequestId(), entity.getRequestType(), entity.getEventId(),
                        entity.getEventName(), entity.getPurpose(), entity.getDescription(),
                        entity.getStartDatetime(), entity.getEndDatetime(), entity.getExpectedAttendance(),
                        entity.getVenueRequirements(), entity.getEquipmentRequirements(),
                        entity.getAccessibilityNeeds(), entity.getRegistrationNeeds(),
                        entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt(),
                        entity.getOrganisation(), entity.getCoordinatorId(), entity.getRejectionReason(), null);
            }
        };
        service = new EventRequestService(
                repository, mapper, eventRepository, userRepository, notificationService, activityService);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // State changes load the row with findForUpdate (row lock); these
        // tests stub findById, so route one to the other.
        when(repository.findForUpdate(any()))
                .thenAnswer(invocation -> repository.findById(invocation.getArgument(0)));
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SaveEventRequestRequest blankRequest() {
        return new SaveEventRequestRequest(null, null, null, null, null, null, null, null, null, null);
    }

    // ---- EO01/EO02 (unchanged behaviour, updated call shape only) --------

    @Test
    void savingANewDraftSucceedsWithEveryFieldMissing() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.status()).isEqualTo(EventRequestStatus.draft);
        assertThat(dto.requestId()).isNotNull();
        assertThat(dto.organisation()).isEqualTo(ORG);
    }

    @Test
    void savingANewDraftRecordsWhoCreatedIt() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenAnswer(inv -> Optional.empty());
        service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        ArgumentCaptor<EventRequest> saved = ArgumentCaptor.forClass(EventRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(CREATED_BY);
    }

    @Test
    void aNewDraftIsNeverVisibleAsSubmittedOrAnyOtherStatus() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.status()).isEqualTo(EventRequestStatus.draft);
    }

    @Test
    void reopeningAndEditingADraftKeepsTheSameId() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        SaveEventRequestRequest edit = new SaveEventRequestRequest(
                "Q1 Town Hall", null, null, null, null, null, null, null, null, null);
        EventRequestDto updated = service.updateDraft(ORG, id, edit);

        assertThat(updated.requestId()).isEqualTo(id);
        assertThat(updated.eventName()).isEqualTo("Q1 Town Hall");
        assertThat(updated.status()).isEqualTo(EventRequestStatus.draft);
    }

    @Test
    void savingChangesUpdatesTheSameDraftRatherThanCreatingANewOne() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        service.updateDraft(ORG, id, blankRequest());

        ArgumentCaptor<EventRequest> saved = ArgumentCaptor.forClass(EventRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRequestId()).isEqualTo(id);
    }

    @Test
    void savingANewDraftSetsBothCreatedAndUpdatedAt() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.updatedAt()).isNotNull();
    }

    @Test
    void editingADraftAdvancesUpdatedAtButNotCreatedAt() {
        UUID id = UUID.randomUUID();
        java.time.OffsetDateTime originalCreatedAt = java.time.OffsetDateTime.now().minusDays(1);
        java.time.OffsetDateTime originalUpdatedAt = java.time.OffsetDateTime.now().minusHours(1);
        EventRequest existing = draftEntity(id, ORG);
        existing.setCreatedAt(originalCreatedAt);
        existing.setUpdatedAt(originalUpdatedAt);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto updated = service.updateDraft(ORG, id, blankRequest());

        assertThat(updated.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.updatedAt()).isAfter(originalUpdatedAt);
    }

    @Test
    void cannotViewOrEditAnotherOrganisationsDraft() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, "Other Org Pte Ltd");
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.get(ORG, id))
                .isInstanceOf(EventRequestNotFoundException.class);
        assertThatThrownBy(() -> service.updateDraft(ORG, id, blankRequest()))
                .isInstanceOf(EventRequestNotFoundException.class);
    }

    @Test
    void savingWithoutAnOrganisationIsRejected() {
        assertThatThrownBy(() -> service.saveNewDraft(null, CREATED_BY, blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
        assertThatThrownBy(() -> service.saveNewDraft("  ", CREATED_BY, blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
    }

    @Test
    void submissionIsBlockedWhileRequiredFieldsAreMissingAndListsWhichOnes() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(ORG, CREATED_BY, id))
                .isInstanceOf(IncompleteEventRequestException.class)
                .satisfies(ex -> assertThat(((IncompleteEventRequestException) ex).getMissingFields())
                        .contains("eventName", "purpose", "startDatetime", "endDatetime",
                                "expectedAttendance", "venueRequirements", "accessibilityNeeds"));
    }

    @Test
    void submissionIsBlockedWhenAccessibilityNeedsWasNeverAnswered() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setAccessibilityNeeds(List.of());
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(ORG, CREATED_BY, id))
                .isInstanceOf(IncompleteEventRequestException.class)
                .satisfies(ex -> assertThat(((IncompleteEventRequestException) ex).getMissingFields())
                        .containsExactly("accessibilityNeeds"));
    }

    @Test
    void submittingACompleteDraftTransitionsItToPending() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto submitted = service.submit(ORG, CREATED_BY, id);

        assertThat(submitted.status()).isEqualTo(EventRequestStatus.pending);
    }

    @Test
    void aRequestThatHasAlreadyLeftDraftCannotBeEditedOrResubmitted() {
        UUID id = UUID.randomUUID();
        EventRequest submitted = completeDraftEntity(id, ORG);
        submitted.setStatus(EventRequestStatus.pending);
        when(repository.findById(id)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.updateDraft(ORG, id, blankRequest()))
                .isInstanceOf(EventRequestNotEditableException.class);
        assertThatThrownBy(() -> service.submit(ORG, CREATED_BY, id))
                .isInstanceOf(EventRequestNotEditableException.class);
    }

    @Test
    void listOnlyReturnsRequestsForTheGivenOrganisation() {
        when(repository.findByOrganisationOrderByCreatedAtDesc(ORG))
                .thenReturn(List.of(draftEntity(UUID.randomUUID(), ORG)));

        List<EventRequestDto> results = service.list(ORG);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).organisation()).isEqualTo(ORG);
    }

    // ---- EO19: coordinator assignment -------------------------------------

    @Test
    void assigningACoordinatorForTheFirstTimeNotifiesTheOrganiserAsInitialNotAReassignment() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCreatedBy(CREATED_BY);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        User coordinator = coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        EventRequestDto result = service.assignCoordinator(id, COORDINATOR_ID);

        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(notificationService).createCoordinatorAssignmentNotification(
                eq(CREATED_BY), eq(id), isNull(), any(), eq("ec1"), eq("ec1@connectsphere.test"), eq(false));
    }

    @Test
    void reassigningToADifferentCoordinatorNotifiesAsAReassignment() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCreatedBy(CREATED_BY);
        existing.setCoordinatorId(OTHER_COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        User coordinator = coordinatorUser(COORDINATOR_ID, "ec2", "ec2@connectsphere.test");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        service.assignCoordinator(id, COORDINATOR_ID);

        verify(notificationService).createCoordinatorAssignmentNotification(
                eq(CREATED_BY), eq(id), isNull(), any(), eq("ec2"), any(), eq(true));
    }

    @Test
    void reassigningTheSameCoordinatorAgainIsANoOpAndSendsNoNotification() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCreatedBy(CREATED_BY);
        existing.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto result = service.assignCoordinator(id, COORDINATOR_ID);

        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(notificationService, never()).createCoordinatorAssignmentNotification(
                any(), any(), any(), any(), any(), any(), anyBoolean());
        // Confirms the AC's "does not receive a duplicate notification" is
        // backed by an actual no-op, not just a skipped notification: no
        // write happens at all when nothing changed.
        verify(repository, never()).save(any());
    }

    @Test
    void assigningANonCoordinatorUserIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        UUID organiserUserId = UUID.randomUUID();
        User notACoordinator = coordinatorUser(organiserUserId, "eo1", "eo1@acme.test");
        notACoordinator.setRole(UserRole.eo);
        when(userRepository.findById(organiserUserId)).thenReturn(Optional.of(notACoordinator));

        assertThatThrownBy(() -> service.assignCoordinator(id, organiserUserId))
                .isInstanceOf(InvalidCoordinatorException.class);
    }

    @Test
    void assigningANonexistentUserIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignCoordinator(id, ghost))
                .isInstanceOf(InvalidCoordinatorException.class);
    }

    @Test
    void assigningWithNoCoordinatorSpecifiedIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.assignCoordinator(id, null))
                .isInstanceOf(InvalidCoordinatorException.class);
    }

    @Test
    void assigningACoordinatorToARequestThatDoesNotExistIsReportedAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignCoordinator(id, COORDINATOR_ID))
                .isInstanceOf(EventRequestNotFoundException.class);
    }

    @Test
    void assigningACoordinatorNeverNotifiesWhenTheRequestHasNoRecordedCreator() {
        // Historical rows saved before createdBy was populated — the
        // notification simply has no valid recipient, not an error.
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setCreatedBy(null);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(userRepository.findById(COORDINATOR_ID))
                .thenReturn(Optional.of(coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test")));

        service.assignCoordinator(id, COORDINATOR_ID);

        verify(notificationService, never()).createCoordinatorAssignmentNotification(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---- EO09: approve / reject --------------------------------------------

    @Test
    void approvingAPendingRequestAssignedToTheCallerCreatesAnEventAndNotifies() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        existing.setCreatedBy(CREATED_BY);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto result = service.approve(COORDINATOR_ID, id);

        assertThat(result.status()).isEqualTo(EventRequestStatus.approved);
        assertThat(result.eventId()).isNotNull();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCoordinatorId()).isEqualTo(COORDINATOR_ID);
        assertThat(eventCaptor.getValue().getEventName()).isEqualTo(existing.getEventName());
        verify(notificationService).createStatusChangeNotification(
                eq(CREATED_BY), eq(id), eq(result.eventId()), any(), eq("approved"), isNull());
    }

    @Test
    void approvingWithoutBeingTheAssignedCoordinatorIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.approve(OTHER_COORDINATOR_ID, id))
                .isInstanceOf(NotAssignedCoordinatorException.class);
        verify(eventRepository, never()).save(any());
        verify(notificationService, never()).createStatusChangeNotification(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvingARequestThatIsNotPendingIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.draft);
        existing.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.approve(COORDINATOR_ID, id))
                .isInstanceOf(EventRequestNotPendingException.class);
    }

    @Test
    void approvingTwiceTheSecondTimeFailsSoAtMostOneApprovalNotificationIsEverSent() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        existing.setCreatedBy(CREATED_BY);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        service.approve(COORDINATOR_ID, id);
        // The entity is now `approved` in memory (repository.save() is
        // stubbed to return the same instance it was given) — a second call
        // against the same mocked findById must see that and refuse.
        assertThatThrownBy(() -> service.approve(COORDINATOR_ID, id))
                .isInstanceOf(EventRequestNotPendingException.class);
        verify(notificationService, times(1)).createStatusChangeNotification(
                any(), any(), any(), any(), eq("approved"), any());
    }

    @Test
    void rejectingRecordsTheReasonAndNotifies() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        existing.setCreatedBy(CREATED_BY);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto result = service.reject(COORDINATOR_ID, id, "Venue unavailable for these dates");

        assertThat(result.status()).isEqualTo(EventRequestStatus.rejected);
        assertThat(result.rejectionReason()).isEqualTo("Venue unavailable for these dates");
        verify(notificationService).createStatusChangeNotification(
                eq(CREATED_BY), eq(id), isNull(), any(), eq("rejected"), eq("Venue unavailable for these dates"));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void rejectingWithoutAReasonIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reject(COORDINATOR_ID, id, null))
                .isInstanceOf(MissingRejectionReasonException.class);
        assertThatThrownBy(() -> service.reject(COORDINATOR_ID, id, "   "))
                .isInstanceOf(MissingRejectionReasonException.class);
        verify(notificationService, never()).createStatusChangeNotification(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectingWithoutBeingTheAssignedCoordinatorIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCoordinatorId(COORDINATOR_ID);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reject(OTHER_COORDINATOR_ID, id, "No capacity"))
                .isInstanceOf(NotAssignedCoordinatorException.class);
    }

    // ---- EC review queue ----------------------------------------------

    @Test
    void reviewQueueSplitsMyRequestsFromUnassignedOnes() {
        when(repository.findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(COORDINATOR_ID, EventRequestStatus.pending))
                .thenReturn(List.of(completeDraftEntity(UUID.randomUUID(), ORG)));
        when(repository.findByStatusAndCoordinatorIdIsNullOrderByCreatedAtAsc(EventRequestStatus.pending))
                .thenReturn(List.of(completeDraftEntity(UUID.randomUUID(), ORG), completeDraftEntity(UUID.randomUUID(), ORG)));

        var queue = service.reviewQueue(COORDINATOR_ID);

        assertThat(queue.needsReview()).hasSize(1);
        assertThat(queue.awaitingOrganiser()).isEmpty();
        assertThat(queue.unassigned()).hasSize(2);
    }

    // ---- EO12/EC03 schedule validation (merged from main) -------------

    @Test
    void reversedRangeCannotBeSubmittedAndDoesNotChangeTheDraft() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setEndDatetime(existing.getStartDatetime().minusMinutes(1));
        var updatedAt = existing.getUpdatedAt();
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.submit(ORG, CREATED_BY, id))
                .isInstanceOf(InvalidEventRequestScheduleException.class);
        assertThat(existing.getStatus()).isEqualTo(EventRequestStatus.draft);
        assertThat(existing.getUpdatedAt()).isEqualTo(updatedAt);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void equalInstantsWithDifferentOffsetsAndPastDatesCanBeSubmitted() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStartDatetime(java.time.OffsetDateTime.parse("2020-09-22T09:00:00Z"));
        existing.setEndDatetime(java.time.OffsetDateTime.parse("2020-09-22T17:00:00+08:00"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        assertThat(service.submit(ORG, CREATED_BY, id).status()).isEqualTo(EventRequestStatus.pending);
    }

    private static EventRequest draftEntity(UUID id, String organisation) {
        EventRequest entity = new EventRequest();
        entity.setRequestId(id);
        entity.setRequestType('C');
        entity.setOrganisation(organisation);
        entity.setStatus(EventRequestStatus.draft);
        entity.setAccessibilityNeeds(List.of());
        return entity;
    }

    private static EventRequest completeDraftEntity(UUID id, String organisation) {
        EventRequest entity = draftEntity(id, organisation);
        entity.setEventName("Q1 Town Hall");
        entity.setPurpose("All-hands update");
        entity.setStartDatetime(java.time.OffsetDateTime.now().plusDays(30));
        entity.setEndDatetime(java.time.OffsetDateTime.now().plusDays(30).plusHours(2));
        entity.setExpectedAttendance(150);
        entity.setVenueRequirements("Theatre-style seating for 150");
        entity.setAccessibilityNeeds(List.of(AccessibilityFeature.none));
        return entity;
    }

    private static User coordinatorUser(UUID id, String username, String email) {
        User user = new User();
        user.setUserId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(UserRole.ec);
        user.setOrganisation("ConnectSphere");
        user.setHashedPassword("irrelevant-for-this-test");
        return user;
    }
}
