package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto;
import com.example.connect_sphere.eventrequest.dto.CoordinatorAssignmentsDto.AssignedRequest;
import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * ECL-C2: the Event Coordinator Lead's view of who holds which request, with
 * every repository mocked. Each test is named after the acceptance criterion
 * it checks, and expected values come from the criteria, not from the code.
 * Which rows the query itself returns is AssignedRequestsQueryTest's job.
 */
@Tag("unit")
class EventRequestAssignmentsServiceTest {

    @Mock private EventRequestRepository repository;
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ActivityService activityService;
    @Captor private ArgumentCaptor<Collection<EventRequestStatus>> askedFor;

    private EventRequestService service;

    private static final OffsetDateTime MARCH_1 = OffsetDateTime.parse("2027-03-01T09:00:00+08:00");
    private static final OffsetDateTime DRAFTED = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
    private static final OffsetDateTime SUBMITTED = OffsetDateTime.parse("2026-09-03T15:30:00+08:00");
    private static final OffsetDateTime ASSIGNED = OffsetDateTime.parse("2026-09-05T08:00:00+08:00");

    private final User aisha = coordinator("aisha");
    private final User bala = coordinator("bala");
    private final User chen = coordinator("chen");

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
        // Stored in no particular order, so the name order is the service's doing.
        when(userRepository.findByRole(UserRole.ec)).thenReturn(List.of(chen, aisha, bala));
    }

    // ---- Who is listed ------------------------------------------------

    @Test
    void everyCoordinatorIsListedInNameOrder() {
        assertThat(service.assignedRequests()).extracting(CoordinatorAssignmentsDto::coordinatorName)
                .containsExactly("aisha", "bala", "chen");
        assertThat(service.assignedRequests()).extracting(CoordinatorAssignmentsDto::coordinatorId)
                .containsExactly(aisha.getUserId(), bala.getUserId(), chen.getUserId());
    }

    @Test
    void aCoordinatorWhoHoldsNoRequestsIsStillListedHoldingNone() {
        underReview(held(chen, "Tech Summit", MARCH_1));

        List<CoordinatorAssignmentsDto> assignments = service.assignedRequests();

        assertThat(assignments).extracting(CoordinatorAssignmentsDto::coordinatorName)
                .containsExactly("aisha", "bala", "chen");
        assertThat(assignments).extracting(a -> a.requests().size()).containsExactly(0, 0, 1);
    }

    @Test
    void whenNothingIsAssignedEveryCoordinatorIsListedHoldingNone() {
        underReview();

        assertThat(service.assignedRequests()).extracting(a -> a.requests().size()).containsExactly(0, 0, 0);
    }

    // ---- Which requests, and where -----------------------------------

    @Test
    void eachRequestSitsUnderItsOwnCoordinatorWhicheverOrganisationItCameFrom() {
        EventRequest acme = held(aisha, "Town Hall", MARCH_1);
        EventRequest globex = held(aisha, "Product Launch", MARCH_1.plusDays(1));
        globex.setOrganisation("Globex Holdings");
        EventRequest forChen = held(chen, "Tech Summit", MARCH_1.plusDays(2));
        underReview(acme, globex, forChen);

        List<CoordinatorAssignmentsDto> assignments = service.assignedRequests();

        assertThat(requestIds(assignments.get(0))).containsExactly(acme.getRequestId(), globex.getRequestId());
        assertThat(assignments.get(0).requests()).extracting(r -> r.request().organisation())
                .containsExactly("Acme Pte Ltd", "Globex Holdings");
        assertThat(requestIds(assignments.get(1))).isEmpty();
        assertThat(requestIds(assignments.get(2))).containsExactly(forChen.getRequestId());
    }

    @Test
    void withinACoordinatorRequestsKeepTheSoonestFirstOrderEvenWhenOtherCoordinatorsRequestsFallBetween() {
        EventRequest soonest = held(aisha, "Town Hall", MARCH_1);
        EventRequest someoneElses = held(bala, "Offsite", MARCH_1.plusDays(1));
        EventRequest middle = held(aisha, "Annual Gala", MARCH_1.plusDays(2));
        EventRequest latest = held(aisha, "Product Launch", MARCH_1.plusDays(3));
        underReview(soonest, someoneElses, middle, latest);

        List<CoordinatorAssignmentsDto> assignments = service.assignedRequests();

        assertThat(requestIds(assignments.get(0)))
                .containsExactly(soonest.getRequestId(), middle.getRequestId(), latest.getRequestId());
        assertThat(requestIds(assignments.get(1))).containsExactly(someoneElses.getRequestId());
    }

    @Test
    void onlyAssignedRequestsThatAreSubmittedOrWaitingOnClarificationAreAskedFor() {
        service.assignedRequests();

        verify(repository).findByCoordinatorIdIsNotNullAndStatusInOrderByStartDatetimeAscRequestIdAsc(
                askedFor.capture());
        assertThat(askedFor.getValue()).containsExactlyInAnyOrder(
                EventRequestStatus.pending, EventRequestStatus.clarification_required);
        // Any other source of requests would be a second repository call.
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = EventRequestStatus.class, names = {"draft", "approved", "rejected", "cancelled"})
    void aRequestThatIsNotUnderReviewIsNeverAskedFor(EventRequestStatus excluded) {
        service.assignedRequests();

        verify(repository).findByCoordinatorIdIsNotNullAndStatusInOrderByStartDatetimeAscRequestIdAsc(
                askedFor.capture());
        assertThat(askedFor.getValue()).doesNotContain(excluded);
    }

    // ---- What each request shows ---------------------------------------

    @Test
    void eachRequestShowsItsEventDetailsAndCurrentStatus() {
        EventRequest waitingOnOrganiser = held(aisha, "Town Hall", MARCH_1);
        waitingOnOrganiser.setStatus(EventRequestStatus.clarification_required);
        underReview(waitingOnOrganiser);

        EventRequestDto shown = service.assignedRequests().get(0).requests().get(0).request();

        assertThat(shown.eventName()).isEqualTo("Town Hall");
        assertThat(shown.organisation()).isEqualTo("Acme Pte Ltd");
        assertThat(shown.startDatetime()).isEqualTo(MARCH_1);
        assertThat(shown.endDatetime()).isEqualTo(MARCH_1.plusHours(3));
        assertThat(shown.expectedAttendance()).isEqualTo(150);
        assertThat(shown.status()).isEqualTo(EventRequestStatus.clarification_required);
        assertThat(shown.coordinatorId()).isEqualTo(aisha.getUserId());
    }

    @Test
    void theDateSubmittedIsWhenTheOrganiserSubmittedNotWhenTheRequestWasAssigned() {
        EventRequest request = held(aisha, "Town Hall", MARCH_1);
        underReview(request);
        when(activityService.submittedAt(List.of(request.getRequestId())))
                .thenReturn(Map.of(request.getRequestId(), SUBMITTED));

        AssignedRequest shown = service.assignedRequests().get(0).requests().get(0);

        assertThat(shown.submittedAt()).isEqualTo(SUBMITTED);
    }

    @Test
    void aRequestSubmittedBeforeTheTimelineExistedFallsBackToWhenItWasCreated() {
        underReview(held(aisha, "Town Hall", MARCH_1));

        assertThat(service.assignedRequests().get(0).requests().get(0).submittedAt()).isEqualTo(DRAFTED);
    }

    // ---- Read-only ------------------------------------------------------

    @Test
    void viewingAssignmentsChangesNothingAndNotifiesNoOne() {
        EventRequest request = held(aisha, "Town Hall", MARCH_1);
        underReview(request);

        service.assignedRequests();

        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        assertThat(request.getCoordinatorId()).isEqualTo(aisha.getUserId());
        assertThat(request.getUpdatedAt()).isEqualTo(ASSIGNED);
        verify(repository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(activityService, never()).record(any());
        verifyNoInteractions(notificationService, eventRepository);
    }

    // ---- Fixtures -----------------------------------------------------

    /** What the query returns, in its order, but only when the service asks
     * for exactly the two under-review statuses. */
    private void underReview(EventRequest... inQueryOrder) {
        when(repository.findByCoordinatorIdIsNotNullAndStatusInOrderByStartDatetimeAscRequestIdAsc(
                argThat(statuses -> statuses != null && Set.copyOf(statuses).equals(
                        Set.of(EventRequestStatus.pending, EventRequestStatus.clarification_required)))))
                .thenReturn(List.of(inQueryOrder));
    }

    private static List<UUID> requestIds(CoordinatorAssignmentsDto assignments) {
        return assignments.requests().stream().map(r -> r.request().requestId()).toList();
    }

    /** A submitted Acme request assigned to {@code coordinator}. */
    private static EventRequest held(User coordinator, String eventName, OffsetDateTime starts) {
        EventRequest entity = new EventRequest();
        entity.setRequestId(UUID.randomUUID());
        entity.setRequestType('C');
        entity.setOrganisation("Acme Pte Ltd");
        entity.setCoordinatorId(coordinator.getUserId());
        entity.setStatus(EventRequestStatus.pending);
        entity.setEventName(eventName);
        entity.setStartDatetime(starts);
        entity.setEndDatetime(starts.plusHours(3));
        entity.setExpectedAttendance(150);
        entity.setCreatedAt(DRAFTED);
        entity.setUpdatedAt(ASSIGNED);
        return entity;
    }

    private static User coordinator(String username) {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setUsername(username);
        user.setRole(UserRole.ec);
        user.setOrganisation("ConnectSphere");
        return user;
    }
}
