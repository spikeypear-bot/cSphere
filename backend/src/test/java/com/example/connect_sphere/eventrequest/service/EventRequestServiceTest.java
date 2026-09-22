package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;

/**
 * Unit tests for EventRequestService, one per EO01/EO02 acceptance criterion
 * (see docs/product-context.md for the full AC text). These don't need a
 * database — the repository is mocked — so they run fast and are the first
 * line of defence; a Postgres-backed integration test is a follow-up (see
 * docs/decision-log.md).
 */
class EventRequestServiceTest {

    @Mock
    private EventRequestRepository repository;

    private EventRequestService service;

    private static final String ORG = "Acme Conferences";

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
                        entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getOrganisation());
            }
        };
        service = new EventRequestService(repository, mapper);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SaveEventRequestRequest blankRequest() {
        return new SaveEventRequestRequest(null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void savingANewDraftSucceedsWithEveryFieldMissing() {
        EventRequestDto dto = service.saveNewDraft(ORG, blankRequest());

        assertThat(dto.status()).isEqualTo(EventRequestStatus.draft);
        assertThat(dto.requestId()).isNotNull();
        assertThat(dto.organisation()).isEqualTo(ORG);
    }

    @Test
    void aNewDraftIsNeverVisibleAsSubmittedOrAnyOtherStatus() {
        EventRequestDto dto = service.saveNewDraft(ORG, blankRequest());

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
        EventRequestDto dto = service.saveNewDraft(ORG, blankRequest());

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
        assertThatThrownBy(() -> service.saveNewDraft(null, blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
        assertThatThrownBy(() -> service.saveNewDraft("  ", blankRequest()))
                .isInstanceOf(MissingOrganisationException.class);
    }

    @Test
    void submissionIsBlockedWhileRequiredFieldsAreMissingAndListsWhichOnes() {
        UUID id = UUID.randomUUID();
        EventRequest existing = draftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(ORG, id))
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

        assertThatThrownBy(() -> service.submit(ORG, id))
                .isInstanceOf(IncompleteEventRequestException.class)
                .satisfies(ex -> assertThat(((IncompleteEventRequestException) ex).getMissingFields())
                        .containsExactly("accessibilityNeeds"));
    }

    @Test
    void submittingACompleteDraftTransitionsItToPending() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EventRequestDto submitted = service.submit(ORG, id);

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
        assertThatThrownBy(() -> service.submit(ORG, id))
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

    @Test
    void reversedRangeCannotBeSubmittedAndDoesNotChangeTheDraft() {
        UUID id = UUID.randomUUID();
        EventRequest existing = completeDraftEntity(id, ORG);
        existing.setEndDatetime(existing.getStartDatetime().minusMinutes(1));
        var updatedAt = existing.getUpdatedAt();
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.submit(ORG, id))
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
        assertThat(service.submit(ORG, id).status()).isEqualTo(EventRequestStatus.pending);
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
}
