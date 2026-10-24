package com.example.connect_sphere.eventrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EO-C2: a request can only be sent (and resubmitted, and approved) when its
 * date, time and expected attendance describe a real event that has not yet
 * started. "Now" is pinned by a fixed clock, so the boundaries are exact:
 * a start at this very moment, an end equal to the start, one minute either
 * side, and the same moment written in another offset. Repositories are
 * stubs; the mapper is a dummy (its output is not what these tests check).
 */
@Tag("unit")
@Tag("EO-C2")
@Tag("EO02")
@Tag("EO26")
@Tag("EC02")
class EventRequestSubmissionRulesTest {

    /** 23 Oct 2026, 10:00 in Singapore. */
    private static final Instant NOW = Instant.parse("2026-10-23T02:00:00Z");
    private static final ZoneOffset SGT = ZoneOffset.ofHours(8);
    private static final String ORG = "Acme Conferences";
    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();

    private final EventRequestRepository repository = mock(EventRequestRepository.class);
    private final ActivityService activity = mock(ActivityService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final EventRequestService service = new EventRequestService(repository, mock(EventRequestMapper.class),
            mock(EventRepository.class), mock(UserRepository.class), notifications, activity,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void saveReturnsTheEntity() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static OffsetDateTime sgt(String local) {
        return OffsetDateTime.parse(local + "+08:00");
    }

    /** A complete request, a week from now, 10:00–12:00 Singapore time. */
    private EventRequest stored(EventRequestStatus status) {
        EventRequest request = new EventRequest();
        request.setRequestId(UUID.randomUUID());
        request.setRequestType('C');
        request.setOrganisation(ORG);
        request.setStatus(status);
        request.setEventName("Town Hall");
        request.setPurpose("Quarterly update");
        request.setStartDatetime(sgt("2026-10-30T10:00:00"));
        request.setEndDatetime(sgt("2026-10-30T12:00:00"));
        request.setExpectedAttendance(150);
        request.setVenueRequirements("Theatre seating");
        request.setAccessibilityNeeds(List.of(AccessibilityFeature.none));
        request.setUpdatedAt(OffsetDateTime.parse("2026-10-20T09:00:00+08:00"));
        when(repository.findById(request.getRequestId())).thenReturn(Optional.of(request));
        when(repository.findForUpdate(request.getRequestId())).thenReturn(Optional.of(request));
        return request;
    }

    private InvalidEventRequestScheduleException refusedOnSubmit(EventRequest request) {
        OffsetDateTime updatedAt = request.getUpdatedAt();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submit(ORG, ORGANISER, request.getRequestId()));
        assertThat(thrown).isInstanceOf(InvalidEventRequestScheduleException.class);
        // Failure changes nothing (EO-C2 AC6, EO02).
        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.draft);
        assertThat(request.getUpdatedAt()).isEqualTo(updatedAt);
        verify(repository, never()).save(any());
        verify(activity, never()).record(any());
        return (InvalidEventRequestScheduleException) thrown;
    }

    private void acceptedOnSubmit(EventRequest request) {
        service.submit(ORG, ORGANISER, request.getRequestId());
        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
    }

    // ---- AC1: the end must be after the start --------------------------------

    @Test
    @DisplayName("EO-C2 AC1 (boundary): an event that ends at the moment it starts is refused, next to the end field")
    void anEndEqualToTheStartIsRefused() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setEndDatetime(request.getStartDatetime());

        assertThat(refusedOnSubmit(request).getFieldErrors())
                .containsExactly(java.util.Map.entry("endDatetime", "The end date & time must be after the start date & time."));
    }

    @Test
    @DisplayName("EO-C2 AC1 (boundary): the same moment written in another time zone is still the same moment, so it is refused")
    void theSameMomentInAnotherOffsetIsRefused() {
        EventRequest request = stored(EventRequestStatus.draft);
        // 10:00 in Singapore is 02:00 UTC: different text, same instant.
        request.setEndDatetime(OffsetDateTime.parse("2026-10-30T02:00:00Z"));

        assertThat(refusedOnSubmit(request).getFieldErrors()).containsOnlyKeys("endDatetime");
    }

    @Test
    @DisplayName("EO-C2 AC1 (negative): an end one minute before the start is refused")
    void anEndBeforeTheStartIsRefused() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setEndDatetime(request.getStartDatetime().minusMinutes(1));

        assertThat(refusedOnSubmit(request).getFieldErrors()).containsOnlyKeys("endDatetime");
    }

    @Test
    @DisplayName("EO-C2 AC1 (boundary): an end one minute after the start is accepted")
    void anEndOneMinuteAfterTheStartIsAccepted() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setEndDatetime(request.getStartDatetime().plusMinutes(1));

        acceptedOnSubmit(request);
    }

    @Test
    @DisplayName("EO-C2 AC1 (happy): an overnight event, ending on the next date, is a valid request")
    void anOvernightEventIsAccepted() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setStartDatetime(sgt("2026-10-30T22:00:00"));
        request.setEndDatetime(sgt("2026-10-31T02:00:00"));

        acceptedOnSubmit(request);
    }

    // ---- AC2: the start must be in the future --------------------------------

    @Test
    @DisplayName("EO-C2 AC2 (boundary): an event starting at this very moment has already started, so it is refused")
    void aStartAtThisMomentIsRefused() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setStartDatetime(OffsetDateTime.ofInstant(NOW, SGT));
        request.setEndDatetime(request.getStartDatetime().plusHours(2));

        assertThat(refusedOnSubmit(request).getFieldErrors())
                .containsExactly(java.util.Map.entry("startDatetime", "The start date & time must be in the future."));
    }

    @Test
    @DisplayName("EO-C2 AC2 (boundary): a start one minute ago is refused; one minute from now is accepted")
    void aStartOneMinuteEitherSideOfNow() {
        EventRequest past = stored(EventRequestStatus.draft);
        past.setStartDatetime(OffsetDateTime.ofInstant(NOW.minusSeconds(60), SGT));
        past.setEndDatetime(past.getStartDatetime().plusHours(2));
        assertThat(refusedOnSubmit(past).getFieldErrors()).containsOnlyKeys("startDatetime");

        EventRequest soon = stored(EventRequestStatus.draft);
        soon.setStartDatetime(OffsetDateTime.ofInstant(NOW.plusSeconds(60), SGT));
        soon.setEndDatetime(soon.getStartDatetime().plusHours(2));
        acceptedOnSubmit(soon);
    }

    @Test
    @DisplayName("EO-C2 AC2 (negative): an event last year is refused, even though its end is after its start")
    void aPastEventIsRefused() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setStartDatetime(sgt("2025-10-30T10:00:00"));
        request.setEndDatetime(sgt("2025-10-30T12:00:00"));

        assertThat(refusedOnSubmit(request).getFieldErrors()).containsOnlyKeys("startDatetime");
    }

    // ---- AC4: at least one person is expected ---------------------------------

    @Test
    @DisplayName("EO-C2 AC4 (boundary): an expected attendance of 0 or -1 is refused; 1 is accepted")
    void attendanceMustBeAtLeastOne() {
        for (int invalid : new int[] {0, -1}) {
            EventRequest request = stored(EventRequestStatus.draft);
            request.setExpectedAttendance(invalid);
            assertThat(refusedOnSubmit(request).getFieldErrors())
                    .containsExactly(java.util.Map.entry("expectedAttendance", "Expected attendance must be at least 1."));
        }
        EventRequest one = stored(EventRequestStatus.draft);
        one.setExpectedAttendance(1);
        acceptedOnSubmit(one);
    }

    // ---- AC6: every problem, next to its field ---------------------------------

    @Test
    @DisplayName("EO-C2 AC6 (negative): every problem is reported at once, each against its own field, in the form's order")
    void everyProblemIsReportedAtOnce() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setStartDatetime(sgt("2025-01-01T10:00:00"));
        request.setEndDatetime(sgt("2025-01-01T09:00:00"));
        request.setExpectedAttendance(0);

        InvalidEventRequestScheduleException refusal = refusedOnSubmit(request);

        assertThat(refusal.getFieldErrors().keySet())
                .containsExactly("startDatetime", "endDatetime", "expectedAttendance");
        assertThat(refusal.getMessage()).contains("in the future").contains("after the start").contains("at least 1");
    }

    @Test
    @DisplayName("EO-C2 AC6 (boundary): a missing field is reported as missing (EO02), not also as invalid")
    void aMissingFieldIsNotAlsoInvalid() {
        EventRequest request = stored(EventRequestStatus.draft);
        request.setEndDatetime(null);

        assertThatThrownBy(() -> service.submit(ORG, ORGANISER, request.getRequestId()))
                .isInstanceOf(IncompleteEventRequestException.class);
    }

    // ---- AC3: the same rules on resubmission and approval --------------------

    @Test
    @DisplayName("EO-C2 AC3 (conflict): a request whose start has passed while waiting for clarification cannot be resubmitted, and nothing changes")
    void resubmittingAfterTheStartHasPassedIsRefused() {
        EventRequest request = stored(EventRequestStatus.clarification_required);
        request.setStartDatetime(sgt("2026-10-23T09:00:00"));
        request.setEndDatetime(sgt("2026-10-23T11:00:00"));

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "Updated as asked."))
                .isInstanceOf(InvalidEventRequestScheduleException.class);
        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.clarification_required);
        verify(repository, never()).save(any());
        verify(notifications, never()).createClarificationRespondedNotification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("EO-C2 AC3 (conflict): edits sent with a resubmission are checked too, and a refusal leaves the stored request untouched")
    void editsSentWithAResubmissionAreChecked() {
        EventRequest request = stored(EventRequestStatus.clarification_required);
        SaveEventRequestRequest edits = new SaveEventRequestRequest("Town Hall", "Quarterly update", null,
                sgt("2026-10-30T10:00:00"), sgt("2026-10-30T10:00:00"), 0, "Theatre seating", null,
                List.of(AccessibilityFeature.none), false);

        assertThatThrownBy(() -> service.resubmit(ORG, ORGANISER, request.getRequestId(), "Updated.", edits))
                .isInstanceOf(InvalidEventRequestScheduleException.class)
                .extracting(e -> ((InvalidEventRequestScheduleException) e).getFieldErrors().keySet())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ITERABLE)
                .containsExactly("endDatetime", "expectedAttendance");
        assertThat(request.getExpectedAttendance()).isEqualTo(150);
        assertThat(request.getEndDatetime()).isEqualTo(sgt("2026-10-30T12:00:00"));
    }

    @Test
    @DisplayName("EO-C2 AC3 (conflict): a coordinator cannot approve a request whose start has passed since it was submitted, and no event is created")
    void approvingAfterTheStartHasPassedIsRefused() {
        EventRequest request = stored(EventRequestStatus.pending);
        request.setCoordinatorId(COORDINATOR);
        request.setStartDatetime(sgt("2026-10-22T10:00:00"));
        request.setEndDatetime(sgt("2026-10-22T12:00:00"));

        assertThatThrownBy(() -> service.approve(COORDINATOR, request.getRequestId()))
                .isInstanceOf(InvalidEventRequestScheduleException.class);
        assertThat(request.getStatus()).isEqualTo(EventRequestStatus.pending);
        assertThat(request.getEventId()).isNull();
    }

    @Test
    @DisplayName("EO-C2 AC3 (happy): the coordinator's review says, per field, why approval is blocked")
    void theReviewExplainsWhyApprovalIsBlocked() {
        EventRequest request = stored(EventRequestStatus.pending);
        request.setCoordinatorId(COORDINATOR);
        request.setStartDatetime(sgt("2026-10-22T10:00:00"));
        request.setEndDatetime(sgt("2026-10-22T10:00:00"));

        var review = service.getForReview(COORDINATOR, request.getRequestId());

        assertThat(review.scheduleValid()).isFalse();
        assertThat(review.scheduleProblems()).containsOnlyKeys("startDatetime", "endDatetime");
    }

    // ---- AC7: drafts stay saveable --------------------------------------------

    @Test
    @DisplayName("EO-C2 AC7 (happy): a draft with a past date, an end before its start or zero attendance can still be saved")
    void anInvalidDraftCanStillBeSaved() {
        SaveEventRequestRequest invalid = new SaveEventRequestRequest("Town Hall", null, null,
                sgt("2025-01-01T10:00:00"), sgt("2025-01-01T09:00:00"), 0, null, null, List.of(), false);

        service.saveNewDraft(ORG, ORGANISER, invalid);

        verify(repository).save(any());
    }
}
