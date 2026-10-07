package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.dto.ActivityDto;
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
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * ECL-C3: the Event Coordinator Lead's review of an unassigned request, with
 * every repository mocked. Each test is named after the acceptance criterion
 * it checks, and expected values come from the criteria, not from the code.
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
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID ORGANISER = UUID.randomUUID();
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

    // ---- Fixtures -----------------------------------------------------

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
}
