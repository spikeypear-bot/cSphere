package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.changerequest.ChangeRequestDto.ImpactItem;
import com.example.connect_sphere.changerequest.ChangeRequestService.Caller;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.EventRequestService;
import com.example.connect_sphere.eventrequest.service.InvalidEventRequestScheduleException;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO03/EC-NEW3 rules with every collaborator replaced. Outside a transaction
 * "after commit" runs at once, so notifications are visible here, which the
 * rolled-back integration tests cannot show. "Now" is a fixed clock.
 */
@Tag("unit")
@Tag("EO03")
@Tag("EC-NEW3")
class ChangeRequestServiceTest {

    /** 23 Oct 2026, 10:00 in Singapore. */
    private static final Instant NOW = Instant.parse("2026-10-23T02:00:00Z");
    private static final String ORG = "Acme";
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();

    private final ChangeRequestRepository changeRequests = mock(ChangeRequestRepository.class);
    private final EventRequestRepository requests = mock(EventRequestRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventRequestService rules = mock(EventRequestService.class);
    private final EventLifecycleService lifecycle = mock(EventLifecycleService.class);
    private final ChangeImpactService impact = mock(ChangeImpactService.class);
    private final ActivityService activity = mock(ActivityService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ChangeRequestService service = new ChangeRequestService(changeRequests, requests, events, rules,
            lifecycle, impact, activity, notifications, users, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Caller organiser = new Caller(UserRole.eo, ORGANISER, ORG);
    private final Caller coordinator = new Caller(UserRole.ec, COORDINATOR, "ConnectSphere");

    @BeforeEach
    void setUp() {
        when(changeRequests.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(changeRequests.save(any())).thenAnswer(i -> i.getArgument(0));
        when(changeRequests.findFirstByRequestIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(users.findById(any())).thenAnswer(i -> Optional.of(user(i.getArgument(0))));
        User lead = user(LEAD);
        when(users.findByRole(UserRole.ecl)).thenReturn(List.of(lead));
        when(impact.assess(any(), any(), any())).thenReturn(List.of());
    }

    private static User user(UUID id) {
        User user = new User();
        user.setUserId(id);
        user.setUsername(id.equals(COORDINATOR) ? "ec1" : id.equals(LEAD) ? "ecl1" : "eo1");
        return user;
    }

    /** A submitted request, 10:00-12:00 Singapore time a week from NOW. */
    private EventRequest submitted(UUID coordinatorId) {
        EventRequest r = new EventRequest();
        r.setRequestId(UUID.randomUUID());
        r.setOrganisation(ORG);
        r.setStatus(EventRequestStatus.pending);
        r.setCoordinatorId(coordinatorId);
        r.setEventName("Town Hall");
        r.setPurpose("Update");
        r.setStartDatetime(OffsetDateTime.parse("2026-10-30T10:00:00+08:00"));
        r.setEndDatetime(OffsetDateTime.parse("2026-10-30T12:00:00+08:00"));
        r.setExpectedAttendance(150);
        r.setVenueRequirements("Theatre");
        r.setAccessibilityNeeds(List.of(AccessibilityFeature.none));
        r.setRegistrationNeeds(false);
        when(requests.findForUpdate(r.getRequestId())).thenReturn(Optional.of(r));
        when(requests.findById(r.getRequestId())).thenReturn(Optional.of(r));
        return r;
    }

    private static SaveEventRequestRequest withAttendance(EventRequest r, int attendance) {
        SaveEventRequestRequest d = EventRequestService.detailsOf(r);
        return new SaveEventRequestRequest(d.eventName(), d.purpose(), d.description(), d.startDatetime(),
                d.endDatetime(), attendance, d.venueRequirements(), d.equipmentRequirements(), d.accessibilityNeeds(),
                d.requiredFacilities(), d.registrationNeeds());
    }

    private ChangeRequest pendingChange(EventRequest r, int attendance) {
        ChangeRequest c = new ChangeRequest();
        c.setChangeRequestId(UUID.randomUUID());
        c.setRequestId(r.getRequestId());
        c.setEventId(r.getEventId());
        c.setRequestedBy(ORGANISER);
        c.setReason("More people");
        c.setOriginalValues(ChangeFields.valuesOf(EventRequestService.detailsOf(r)));
        c.setProposedValues(ChangeFields.valuesOf(withAttendance(r, attendance)));
        c.setStatus(ChangeRequestStatus.pending);
        c.setRequestedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(changeRequests.findForUpdate(c.getChangeRequestId())).thenReturn(Optional.of(c));
        when(changeRequests.findById(c.getChangeRequestId())).thenReturn(Optional.of(c));
        return c;
    }

    // ---- asking ------------------------------------------------------------------

    @Test
    @DisplayName("EO03 AC5 (boundary): an event starting at this very moment has started and cannot be changed; one starting a minute later can")
    void theStartBoundary() {
        EventRequest now = submitted(COORDINATOR);
        now.setStartDatetime(OffsetDateTime.ofInstant(NOW, ZoneOffset.ofHours(8)));
        assertThatThrownBy(() -> service.create(organiser, now.getRequestId(), "x", withAttendance(now, 160)))
                .isInstanceOf(ChangeRequestException.State.class)
                .hasMessage("The event has already started, so it can no longer be changed.");

        EventRequest soon = submitted(COORDINATOR);
        soon.setStartDatetime(OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.ofHours(8)));
        assertThat(service.create(organiser, soon.getRequestId(), "x", withAttendance(soon, 160)).status())
                .isEqualTo("pending");
    }

    @Test
    @DisplayName("EO03 AC31, AC40 (happy): asking tells the assigned coordinator what would change and why, and logs the change as Major")
    void askingNotifiesTheCoordinator() {
        EventRequest r = submitted(COORDINATOR);

        service.create(organiser, r.getRequestId(), "  More people  ", withAttendance(r, 200));

        verify(notifications).createChangeRequestNotifications(eq(List.of(COORDINATOR)),
                eq(NotificationType.change_request_submitted), eq(r.getRequestId()), any(), eq("Town Hall"),
                eq("Change requested: Expected attendance: 150 → 200. Reason: More people"));
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.change_requested);
        assertThat(entry.getValue().fieldValues()).containsOnlyKeys("expectedAttendance");
    }

    @Test
    @DisplayName("EO03 AC32 (happy): with no coordinator yet, every Event Coordinator Lead is told instead")
    void askingWithoutACoordinatorTellsTheLeads() {
        EventRequest r = submitted(null);

        service.create(organiser, r.getRequestId(), "More people", withAttendance(r, 200));

        verify(notifications).createChangeRequestNotifications(eq(List.of(LEAD)),
                eq(NotificationType.change_request_submitted), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EO03 AC27 (conflict): when two submissions race, the database keeps one and the other is told one is already waiting")
    void aRacingSecondSubmissionIsRefused() {
        EventRequest r = submitted(COORDINATOR);
        when(changeRequests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("idx_one_pending_change_request"));

        assertThatThrownBy(() -> service.create(organiser, r.getRequestId(), "x", withAttendance(r, 160)))
                .isInstanceOf(ChangeRequestException.State.class)
                .hasMessageContaining("already has a change request waiting");
        verify(activity, never()).record(any());
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EO03 AC13, AC42 (failure): invalid details are refused before anything is saved, logged or sent")
    void invalidDetailsChangeNothing() {
        EventRequest r = submitted(COORDINATOR);
        SaveEventRequestRequest invalid = withAttendance(r, 0);
        doThrow(new InvalidEventRequestScheduleException(Map.of("expectedAttendance", "Expected attendance must be at least 1.")))
                .when(rules).requireValidDetails(invalid);

        assertThatThrownBy(() -> service.create(organiser, r.getRequestId(), "x", invalid))
                .isInstanceOf(InvalidEventRequestScheduleException.class);
        verify(changeRequests, never()).saveAndFlush(any());
        verify(activity, never()).record(any());
        verifyNoInteractions(notifications);
    }

    // ---- withdrawing and deciding ---------------------------------------------------

    @Test
    @DisplayName("EO03 AC34, EC-NEW3 AC41 (happy): withdrawing tells the coordinator")
    void withdrawingTellsTheCoordinator() {
        EventRequest r = submitted(COORDINATOR);
        ChangeRequest c = pendingChange(r, 200);

        service.withdraw(organiser, c.getChangeRequestId());

        assertThat(c.getStatus()).isEqualTo(ChangeRequestStatus.withdrawn);
        verify(notifications).createChangeRequestNotifications(eq(List.of(COORDINATOR)),
                eq(NotificationType.change_request_withdrawn), eq(r.getRequestId()), any(), any(),
                eq("The organiser withdrew their change request."));
    }

    @Test
    @DisplayName("EC-NEW3 AC29, AC43 (failure): approving without acknowledging a new impact is refused, and nothing is applied, logged or sent")
    void approvingWithoutAcknowledgingChangesNothing() {
        EventRequest r = submitted(COORDINATOR);
        ChangeRequest c = pendingChange(r, 200);
        when(impact.assess(any(), any(), any())).thenReturn(List.of(
                new ImpactItem("venue:x:capacity", "venue", "attention", "Hall is too small", "It holds 180")));

        assertThatThrownBy(() -> service.approve(coordinator, c.getChangeRequestId(), List.of()))
                .isInstanceOf(ChangeRequestException.ImpactChanged.class)
                .satisfies(e -> assertThat(((ChangeRequestException.ImpactChanged) e).getImpact()).hasSize(1));
        assertThat(c.getStatus()).isEqualTo(ChangeRequestStatus.pending);
        verify(rules, never()).applyApprovedDetails(any(), any(), any());
        verify(activity, never()).record(any());
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EC-NEW3 AC26, AC34, AC37, AC39, EO03 AC38 (happy): approving an important change to a confirmed event records what was acknowledged, re-plans it and tells the organiser")
    void approvingReplansAndTellsTheOrganiser() {
        EventRequest r = submitted(COORDINATOR);
        r.setStatus(EventRequestStatus.approved);
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setStatus(EventStatus.confirmed);
        event.setCoordinatorId(COORDINATOR);
        event.setEventName("Town Hall");
        event.setStartDatetime(r.getStartDatetime());
        event.setEndDatetime(r.getEndDatetime());
        r.setEventId(event.getEventId());
        when(events.findForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        ChangeRequest c = pendingChange(r, 200);
        when(impact.assess(any(), any(), any())).thenReturn(List.of(
                new ImpactItem("safety:replan", "safety", "attention", "The event goes back to Planning", "…")));

        service.approve(coordinator, c.getChangeRequestId(), List.of("safety:replan"));

        assertThat(c.getStatus()).isEqualTo(ChangeRequestStatus.approved);
        assertThat(c.getDecidedBy()).isEqualTo(COORDINATOR);
        assertThat(c.getAcknowledgedImpacts()).extracting(m -> m.get("key")).containsExactly("safety:replan");
        verify(rules).applyApprovedDetails(eq(r), eq(event), argThat(d -> d.expectedAttendance() == 200));
        verify(lifecycle).returnToPlanning(eq(event.getEventId()), eq(COORDINATOR), argThat(s -> s.contains("150 → 200")));
        verify(notifications).createChangeRequestNotifications(eq(List.of(ORGANISER)),
                eq(NotificationType.change_request_decided), eq(r.getRequestId()), eq(event.getEventId()), any(),
                eq("Your change request was approved: Expected attendance: 150 → 200. The arrangements will be re-confirmed."));
    }

    @Test
    @DisplayName("EC-NEW3 AC35 (happy): approving a change while the event is still in Planning does not touch its status")
    void approvingInPlanningDoesNotReplan() {
        EventRequest r = submitted(COORDINATOR);

        service.approve(coordinator, pendingChange(r, 200).getChangeRequestId(), List.of());

        verify(lifecycle, never()).returnToPlanning(any(), any(), any());
    }

    @Test
    @DisplayName("EC-NEW3 AC25, AC39 (happy): rejecting tells the organiser the coordinator's reason")
    void rejectingTellsTheOrganiserWhy() {
        EventRequest r = submitted(COORDINATOR);
        ChangeRequest c = pendingChange(r, 400);

        service.reject(coordinator, c.getChangeRequestId(), "  No hall holds 400  ");

        assertThat(c.getDecisionReason()).isEqualTo("No hall holds 400");
        verify(rules, never()).applyApprovedDetails(any(), any(), any());
        verify(notifications).createChangeRequestNotifications(anyList(), eq(NotificationType.change_request_decided),
                any(), any(), any(), eq("Your change request was rejected: No hall holds 400"));
    }

    @Test
    @DisplayName("EC-NEW3 AC1 (negative): a coordinator who is not assigned cannot reject, and nothing changes")
    void anotherCoordinatorCannotDecide() {
        EventRequest r = submitted(COORDINATOR);
        ChangeRequest c = pendingChange(r, 400);

        assertThatThrownBy(() -> service.reject(new Caller(UserRole.ec, UUID.randomUUID(), "ConnectSphere"),
                c.getChangeRequestId(), "No"))
                .isInstanceOf(com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException.class);
        assertThat(c.getStatus()).isEqualTo(ChangeRequestStatus.pending);
    }
}
