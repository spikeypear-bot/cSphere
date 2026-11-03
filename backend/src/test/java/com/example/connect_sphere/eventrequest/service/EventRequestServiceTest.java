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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
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

import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.CoordinatorDto;
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
@Tag("unit")
@Tag("EO01")
@Tag("EO02")
@Tag("EO-C2")
@Tag("EO09")
@Tag("EO19")
@Tag("ELC-C6")
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
    private static final UUID LEAD_ID = UUID.randomUUID();

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
                repository, mapper, eventRepository, userRepository, notificationService, activityService, Clock.systemUTC());
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
    @DisplayName("EO01 (happy): the picker stores catalogue IDs and quantities alongside compatible requirements text")
    void pickerItemsAreRetainedOnDraftSave() {
        var item = new com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest.Item(UUID.randomUUID(), 3);
        var input = new SaveEventRequestRequest("Workshop", null, null, null, null, null, null,
                "Equipment items:\n3 × Audio mixer\n\nAdditional notes:\nBring cables", List.of(), List.of(),
                false, null, null, null, null, List.of(item));
        service.saveNewDraft(ORG, CREATED_BY, input);
        var saved = ArgumentCaptor.forClass(EventRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEquipmentItems()).containsExactly(item);
        assertThat(saved.getValue().getEquipmentRequirements()).contains("Bring cables");
    }

    @Test
    @DisplayName("EO01 (boundary): legacy saves retain selected items and an explicit empty selection clears them")
    void equipmentSelectionIsNotLostByALegacyDraftEdit() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, ORG);
        var item = new com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest.Item(UUID.randomUUID(), 2);
        existing.setEquipmentItems(new java.util.ArrayList<>(List.of(item)));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        service.updateDraft(ORG, id, blankRequest());
        assertThat(existing.getEquipmentItems()).containsExactly(item);
        service.updateDraft(ORG, id, new SaveEventRequestRequest(null, null, null, null, null, null,
                null, null, List.of(), List.of(), false, null, null, null, null, List.of()));
        assertThat(existing.getEquipmentItems()).isEmpty();
    }

    @Test
    @DisplayName("EO01 (boundary): saving a new draft succeeds with every field missing")
    void savingANewDraftSucceedsWithEveryFieldMissing() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.status()).isEqualTo(EventRequestStatus.draft);
        assertThat(dto.requestId()).isNotNull();
        assertThat(dto.organisation()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("EO01 (happy): saving a new draft records who created it")
    void savingANewDraftRecordsWhoCreatedIt() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenAnswer(inv -> Optional.empty());
        service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        ArgumentCaptor<EventRequest> saved = ArgumentCaptor.forClass(EventRequest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(CREATED_BY);
    }

    @Test
    @DisplayName("EO01 (happy): a new draft is saved as Draft, never as Submitted or any other status")
    void aNewDraftIsNeverVisibleAsSubmittedOrAnyOtherStatus() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.status()).isEqualTo(EventRequestStatus.draft);
    }

    @Test
    @DisplayName("EO01 (happy): reopening and editing a draft keeps the same request")
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
    @DisplayName("EO01 (happy): saving changes updates the same draft rather than creating a new one")
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
    @DisplayName("EO01 (happy): saving a new draft records both when it was created and when it was last updated")
    void savingANewDraftSetsBothCreatedAndUpdatedAt() {
        EventRequestDto dto = service.saveNewDraft(ORG, CREATED_BY, blankRequest());

        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("EO01 (happy): editing a draft moves its last-updated time forward but keeps its created time")
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
    @DisplayName("EO01 (negative): an organiser cannot view or edit another organisation's draft")
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
    @DisplayName("EO01 (negative): saving without an organisation is refused")
    void savingWithoutAnOrganisationIsRejected() {
        assertThatThrownBy(() -> service.saveNewDraft(null, CREATED_BY, blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
        assertThatThrownBy(() -> service.saveNewDraft("  ", CREATED_BY, blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
    }

    @Test
    @DisplayName("EO02 (negative): submission is blocked while required fields are missing, and lists which ones")
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
    @DisplayName("EO02 (negative): submission is blocked when accessibility needs were never answered")
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
    @DisplayName("EO02 (happy): submitting a complete draft makes it Submitted")
    void submittingACompleteDraftTransitionsItToPending() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto submitted = service.submit(ORG, CREATED_BY, id);

        assertThat(submitted.status()).isEqualTo(EventRequestStatus.pending);
    }

    @Test
    @DisplayName("EO02 (negative): a request that has already left Draft cannot be edited or submitted again")
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
    @DisplayName("EO01 (negative): the list shows only the organiser's own organisation's requests")
    void listOnlyReturnsRequestsForTheGivenOrganisation() {
        when(repository.findByOrganisationOrderByCreatedAtDesc(ORG))
                .thenReturn(List.of(draftEntity(UUID.randomUUID(), ORG)));

        List<EventRequestDto> results = service.list(ORG);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).organisation()).isEqualTo(ORG);
    }

    // ---- EO19 / ELC-C6: coordinator assignment, by the Lead ----------------

    /** A request as the Lead assigns it: submitted by CREATED_BY, nobody assigned. */
    private EventRequest awaitingAssignment(UUID id) {
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStatus(EventRequestStatus.pending);
        existing.setCreatedBy(CREATED_BY);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        return existing;
    }

    /** A refused assignment writes nothing and tells nobody. */
    private void assertNoAssignmentWasMade(EventRequest request, UUID coordinatorId) {
        assertThat(request.getCoordinatorId()).isEqualTo(coordinatorId);
        verify(repository, never()).save(any());
        verifyNoInteractions(activityService, notificationService);
    }

    @Test
    @DisplayName("EO19 ELC-C6 (happy): assigning a coordinator tells the organiser the coordinator's name and contact, as a first assignment")
    void assigningACoordinatorNotifiesTheOrganiserWithTheirNameAndContactAsAnInitialAssignment() {
        UUID id = UUID.randomUUID();
        awaitingAssignment(id);
        User coordinator = coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        EventRequestDto result = service.assignCoordinator(LEAD_ID, id, COORDINATOR_ID);

        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(notificationService).createCoordinatorAssignmentNotification(
                eq(CREATED_BY), eq(id), isNull(), any(), eq("ec1"), eq("ec1@connectsphere.test"), eq(false));
        // EC-NEW4: and the coordinator who now has it; nobody else.
        verify(notificationService).createRequestAssignedNotification(eq(COORDINATOR_ID), eq(id), isNull(), any(),
                eq(false), any());
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("ELC-C6 (conflict): a request that already has a coordinator cannot be assigned to another one")
    void aRequestThatAlreadyHasACoordinatorCannotBeAssignedToAnotherOne() {
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);
        existing.setCoordinatorId(OTHER_COORDINATOR_ID);
        User coordinator = coordinatorUser(COORDINATOR_ID, "ec2", "ec2@connectsphere.test");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, COORDINATOR_ID))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been assigned");
        assertNoAssignmentWasMade(existing, OTHER_COORDINATOR_ID);
    }

    @Test
    @DisplayName("EO19 ELC-C6 (conflict): assigning the coordinator a request already has is refused and sends no notification")
    void assigningTheCoordinatorARequestAlreadyHasIsRefusedAndSendsNoNotification() {
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);
        existing.setCoordinatorId(COORDINATOR_ID);
        User coordinator = coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test");
        when(userRepository.findById(COORDINATOR_ID)).thenReturn(Optional.of(coordinator));

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, COORDINATOR_ID))
                .isInstanceOf(EventRequestStateException.class)
                .hasMessageContaining("already been assigned");
        assertNoAssignmentWasMade(existing, COORDINATOR_ID);
    }

    @Test
    @DisplayName("ELC-C6 (negative): assigning a user who is not an Event Coordinator is refused and nothing changes")
    void assigningANonCoordinatorUserIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);
        UUID organiserUserId = UUID.randomUUID();
        User notACoordinator = coordinatorUser(organiserUserId, "eo1", "eo1@acme.test");
        notACoordinator.setRole(UserRole.eo);
        when(userRepository.findById(organiserUserId)).thenReturn(Optional.of(notACoordinator));

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, organiserUserId))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessageContaining("is not an Event Coordinator");
        assertNoAssignmentWasMade(existing, null);
    }

    @Test
    @DisplayName("ELC-C6 (negative): assigning a user that does not exist is refused and nothing changes")
    void assigningANonexistentUserIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, ghost))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessageContaining("is not an Event Coordinator");
        assertNoAssignmentWasMade(existing, null);
    }

    @Test
    @DisplayName("ELC-C6 (negative): assigning without choosing a coordinator is refused and nothing changes")
    void assigningWithNoCoordinatorSpecifiedIsRejected() {
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, null))
                .isInstanceOf(InvalidCoordinatorException.class)
                .hasMessage("A coordinator must be specified");
        assertNoAssignmentWasMade(existing, null);
    }

    @Test
    @DisplayName("ELC-C6 (negative): assigning a coordinator to a request that does not exist is reported as not found")
    void assigningACoordinatorToARequestThatDoesNotExistIsReportedAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignCoordinator(LEAD_ID, id, COORDINATOR_ID))
                .isInstanceOf(EventRequestNotFoundException.class);
        verifyNoInteractions(activityService, notificationService);
    }

    @Test
    @DisplayName("EO19 (boundary): assigning a coordinator to a request with no recorded creator still works but notifies no organiser")
    void assigningACoordinatorNeverNotifiesWhenTheRequestHasNoRecordedCreator() {
        // Historical rows saved before createdBy was populated — the
        // notification simply has no valid recipient, not an error.
        UUID id = UUID.randomUUID();
        EventRequest existing = awaitingAssignment(id);
        existing.setCreatedBy(null);
        when(userRepository.findById(COORDINATOR_ID))
                .thenReturn(Optional.of(coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test")));

        EventRequestDto result = service.assignCoordinator(LEAD_ID, id, COORDINATOR_ID);

        assertThat(result.coordinatorId()).isEqualTo(COORDINATOR_ID);
        verify(notificationService, never()).createCoordinatorAssignmentNotification(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("ELC-C6 (happy): the Lead's coordinator list is every Event Coordinator, in username order")
    void theLeadsCoordinatorListIsEveryEventCoordinatorByUsername() {
        when(userRepository.findByRole(UserRole.ec)).thenReturn(List.of(
                coordinatorUser(OTHER_COORDINATOR_ID, "ec2", "ec2@connectsphere.test"),
                coordinatorUser(COORDINATOR_ID, "ec1", "ec1@connectsphere.test")));

        assertThat(service.coordinators()).containsExactly(
                new CoordinatorDto(COORDINATOR_ID, "ec1"), new CoordinatorDto(OTHER_COORDINATOR_ID, "ec2"));
        verifyNoInteractions(repository, notificationService, activityService);
    }

    // ---- EO09: approve / reject --------------------------------------------

    @Test
    @DisplayName("EO09 (happy): approving a submitted request assigned to the caller creates an event and notifies the organiser")
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
    @DisplayName("EO09 (negative): approving without being the assigned coordinator is refused, no event is created and no one is notified")
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
    @DisplayName("EO09 (negative): approving a request that is not submitted is refused")
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
    @DisplayName("EO09 (conflict): approving twice fails the second time, so at most one approval notification is ever sent")
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
    @DisplayName("EO09 (happy): rejecting records the reason and notifies the organiser with it")
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
    @DisplayName("EO09 (negative): rejecting without a reason is refused and no one is notified")
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
    @DisplayName("EO09 (negative): rejecting without being the assigned coordinator is refused")
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
    @DisplayName("ELC-C6 (happy): a coordinator's review queue holds only their own requests, split by who needs to act")
    void reviewQueueHoldsOnlyTheCallersOwnRequestsSplitByWhoNeedsToAct() {
        EventRequest mine = completeDraftEntity(UUID.randomUUID(), ORG);
        EventRequest waiting = completeDraftEntity(UUID.randomUUID(), ORG);
        when(repository.findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(COORDINATOR_ID, EventRequestStatus.pending))
                .thenReturn(List.of(mine));
        when(repository.findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(
                COORDINATOR_ID, EventRequestStatus.clarification_required)).thenReturn(List.of(waiting));

        var queue = service.reviewQueue(COORDINATOR_ID);

        assertThat(queue.needsReview()).extracting(EventRequestDto::requestId).containsExactly(mine.getRequestId());
        assertThat(queue.awaitingOrganiser()).extracting(EventRequestDto::requestId)
                .containsExactly(waiting.getRequestId());
        // ELC-C6: nothing is read but the caller's own requests, so no unassigned ones.
        verify(repository).findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(COORDINATOR_ID, EventRequestStatus.pending);
        verify(repository).findByCoordinatorIdAndStatusOrderByUpdatedAtAsc(
                COORDINATOR_ID, EventRequestStatus.clarification_required);
        verifyNoMoreInteractions(repository);
    }

    // ---- ECL-C1 unassigned queue (Event Coordinator Lead) ---------------

    private static final String OTHER_ORG = "Globex Holdings";

    /** A request as it sits in the Lead's queue: submitted, nobody assigned. */
    private static EventRequest submittedUnassigned(String organisation) {
        EventRequest entity = completeDraftEntity(UUID.randomUUID(), organisation);
        entity.setStatus(EventRequestStatus.pending);
        return entity;
    }

    /** The two states an unassigned request can wait in (ELC-C6). */
    private static final List<EventRequestStatus> UNASSIGNED_STATUSES =
            List.of(EventRequestStatus.pending, EventRequestStatus.clarification_required);

    @Test
    void unassignedRequestsListsEveryOrganisationsWaitingRequestsLongestWaitingFirst() {
        EventRequest waitingLongest = submittedUnassigned(ORG);
        EventRequest submittedLater = submittedUnassigned(OTHER_ORG);
        when(repository.findByStatusInAndCoordinatorIdIsNullOrderByUpdatedAtAsc(UNASSIGNED_STATUSES))
                .thenReturn(List.of(waitingLongest, submittedLater));

        List<EventRequestDto> queue = service.unassignedRequests();

        // Same requests, same order, both organisations.
        assertThat(queue).extracting(EventRequestDto::requestId)
                .containsExactly(waitingLongest.getRequestId(), submittedLater.getRequestId());
        assertThat(queue).extracting(EventRequestDto::organisation).containsExactly(ORG, OTHER_ORG);
    }

    @Test
    void unassignedRequestsAsksOnlyForSubmittedOrClarifyingRequestsWithNoCoordinator() {
        service.unassignedRequests();

        // Any other status or ordering would be a second repository call.
        verify(repository).findByStatusInAndCoordinatorIdIsNullOrderByUpdatedAtAsc(UNASSIGNED_STATUSES);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aRequestWaitingForClarificationIsListedWithThatStatus() {
        EventRequest clarifying = submittedUnassigned(ORG);
        clarifying.setStatus(EventRequestStatus.clarification_required);
        when(repository.findByStatusInAndCoordinatorIdIsNullOrderByUpdatedAtAsc(UNASSIGNED_STATUSES))
                .thenReturn(List.of(clarifying));

        assertThat(service.unassignedRequests()).extracting(EventRequestDto::status)
                .containsExactly(EventRequestStatus.clarification_required);
    }

    @Test
    void unassignedRequestsIsAnEmptyListWhenNothingIsWaiting() {
        when(repository.findByStatusInAndCoordinatorIdIsNullOrderByUpdatedAtAsc(UNASSIGNED_STATUSES))
                .thenReturn(List.of());

        assertThat(service.unassignedRequests()).isEmpty();
    }

    @Test
    void viewingUnassignedRequestsChangesNothingAndNotifiesNoOne() {
        EventRequest waiting = submittedUnassigned(ORG);
        when(repository.findByStatusInAndCoordinatorIdIsNullOrderByUpdatedAtAsc(UNASSIGNED_STATUSES))
                .thenReturn(List.of(waiting));

        service.unassignedRequests();

        assertThat(waiting.getStatus()).isEqualTo(EventRequestStatus.pending);
        assertThat(waiting.getCoordinatorId()).isNull();
        verify(repository, never()).save(any());
        verifyNoInteractions(notificationService, activityService, eventRepository, userRepository);
    }

    // ---- EO12/EC03 schedule validation (merged from main) -------------

    @Test
    @DisplayName("EO02 EO-C2 (negative): an end before the start cannot be submitted and the draft is unchanged")
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

    /** Superseded by EO-C2 (decision log D28): equal instants and past dates
     * were allowed by the 22 Sep 2026 date-range decision; both are now refused. */
    @Test
    @DisplayName("EO-C2 (boundary): an end at the same moment as the start, written in another time zone, and past dates are refused")
    void equalInstantsWithDifferentOffsetsAndPastDatesAreRefused() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setStartDatetime(java.time.OffsetDateTime.parse("2020-09-22T09:00:00Z"));
        existing.setEndDatetime(java.time.OffsetDateTime.parse("2020-09-22T17:00:00+08:00"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.submit(ORG, CREATED_BY, id))
                .isInstanceOf(InvalidEventRequestScheduleException.class)
                .satisfies(e -> assertThat(((InvalidEventRequestScheduleException) e).getFieldErrors())
                        .containsOnlyKeys("startDatetime", "endDatetime"));
        assertThat(existing.getStatus()).isEqualTo(EventRequestStatus.draft);
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
