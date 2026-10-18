package com.example.connect_sphere.common.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;

/** EC-NEW2: every stored status maps to exactly one journey status. */
@Tag("unit")
@Tag("EC-NEW2")
class JourneyStatusResolverTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private final EventRepository events = mock(EventRepository.class);
    private final JourneyStatusResolver resolver = new JourneyStatusResolver(events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("EC-NEW2 AC5, AC19 (happy): a submitted, unassigned request is Submitted and waits for the Lead")
    void aSubmittedRequestWithNoCoordinatorIsSubmittedAndWaitsForTheLead() {
        StatusView status = resolver.forRequest(request(EventRequestStatus.pending, null));

        assertThat(status.code()).isEqualTo("SUBMITTED");
        assertThat(status.nextActor()).isEqualTo("Event Coordinator Lead");
    }

    @Test
    @DisplayName("EC-NEW2 AC6 (happy): a request with an assigned coordinator is Under Review")
    void aSubmittedRequestWithACoordinatorIsUnderReview() {
        assertThat(resolver.forRequest(request(EventRequestStatus.pending, UUID.randomUUID())).code())
                .isEqualTo("UNDER_REVIEW");
    }

    @ParameterizedTest
    @CsvSource({"draft,DRAFT", "clarification_required,CLARIFICATION_REQUIRED", "rejected,REJECTED",
            "cancelled,CANCELLED"})
    @DisplayName("EC-NEW2 AC3, AC7, AC28 (happy): every other stored request status maps to exactly one status in the request set")
    void otherRequestStatusesMapOneToOne(EventRequestStatus stored, String expected) {
        assertThat(resolver.forRequest(request(stored, UUID.randomUUID())).code()).isEqualTo(expected);
    }

    @Test
    @DisplayName("EC-NEW2 AC1, AC18 (happy): an approved request shows its event's status, so request and event never disagree")
    void anApprovedRequestShowsItsEventsStatus() {
        Event event = event(EventStatus.awaiting_safety_review, "2027-03-10T04:00:00Z");
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        EventRequest request = request(EventRequestStatus.approved, UUID.randomUUID());
        request.setEventId(event.getEventId());

        assertThat(resolver.forRequest(request).label()).isEqualTo("Awaiting Safety Review");
    }

    @ParameterizedTest
    @CsvSource({"pending,PLANNING", "awaiting_safety_review,AWAITING_SAFETY_REVIEW",
            "safety_changes_requested,SAFETY_CHANGES_REQUESTED", "safety_approved,SAFETY_APPROVED",
            "confirmed,CONFIRMED", "cancelled,CANCELLED"})
    @DisplayName("EC-NEW2 AC4, AC8–AC12, AC27 (happy): every stored event status maps to exactly one status in the event set")
    void eventStatusesMapOneToOne(EventStatus stored, String expected) {
        assertThat(resolver.forEvent(event(stored, "2027-03-10T04:00:00Z")).code()).isEqualTo(expected);
    }

    @Test
    @DisplayName("EC-NEW2 AC13 (happy): a Confirmed event becomes Completed once its end has passed")
    void aConfirmedEventBecomesCompletedOnceItsEndHasPassed() {
        assertThat(resolver.forEvent(event(EventStatus.confirmed, "2026-12-31T23:59:59Z")).code())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("EC-NEW2 AC13 (boundary): an event ending exactly now is still Confirmed")
    void aConfirmedEventEndingExactlyNowIsStillConfirmed() {
        assertThat(resolver.forEvent(event(EventStatus.confirmed, "2027-01-01T00:00:00Z")).code())
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("EC-NEW2 AC14 (boundary): an unconfirmed event whose date has passed is flagged but keeps its status")
    void anUnconfirmedEventWhoseDateHasPassedIsFlaggedButKeepsItsStatus() {
        StatusView status = resolver.forEvent(event(EventStatus.pending, "2026-12-31T00:00:00Z"));

        assertThat(status.code()).isEqualTo("PLANNING");
        assertThat(status.datePassed()).isTrue();
    }

    @Test
    @DisplayName("EC-NEW2 AC29 (negative): Rejected, Cancelled and Completed offer no next actor")
    void finalStatusesOfferNoNextActor() {
        StatusView status = resolver.forEvent(event(EventStatus.cancelled, "2027-03-10T04:00:00Z"));

        assertThat(status.isFinal()).isTrue();
        assertThat(status.nextActor()).isNull();
    }

    @Test
    @DisplayName("EC-NEW2 AC19, AC20, AC28 (cross-cutting): every status has a text label and a one-line explanation")
    void everyJourneyStatusHasALabelAndAnExplanation() {
        for (JourneyStatus status : JourneyStatus.values()) {
            assertThat(status.label()).isNotBlank();
            assertThat(status.explanation()).isNotBlank();
        }
    }

    private static EventRequest request(EventRequestStatus status, UUID coordinator) {
        EventRequest request = new EventRequest();
        request.setStatus(status);
        request.setCoordinatorId(coordinator);
        return request;
    }

    private static Event event(EventStatus status, String end) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setStatus(status);
        event.setEndDatetime(OffsetDateTime.parse(end));
        return event;
    }

    @Test
    @DisplayName("EC-NEW2 AC28 (boundary): a request with no stored status is shown as Draft, never without a status")
    void aRequestWithNoStatusIsDraft() {
        assertThat(resolver.forRequest(null).code()).isEqualTo("DRAFT");
        assertThat(resolver.forRequest(request(null, null)).code()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("EC-NEW2 AC8, AC28 (boundary): an approved request whose event is missing still shows Planning")
    void anApprovedRequestWithoutItsEventShowsPlanning() {
        EventRequest noEvent = request(EventRequestStatus.approved, UUID.randomUUID());
        EventRequest missingEvent = request(EventRequestStatus.approved, UUID.randomUUID());
        missingEvent.setEventId(UUID.randomUUID());
        when(events.findById(missingEvent.getEventId())).thenReturn(Optional.empty());

        assertThat(resolver.forRequest(noEvent).code()).isEqualTo("PLANNING");
        assertThat(resolver.forRequest(missingEvent).code()).isEqualTo("PLANNING");
    }

    @Test
    @DisplayName("EC-NEW2 AC13 (happy): an event stored as completed is Completed")
    void aStoredCompletedEventIsCompleted() {
        assertThat(resolver.forEvent(event(EventStatus.completed, "2026-12-31T00:00:00Z")).code()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("EC-NEW2 AC13, AC14 (boundary): with no end time an event is neither Completed nor flagged")
    void anEventWithNoEndIsNeitherCompletedNorFlagged() {
        Event confirmed = event(EventStatus.confirmed, "2026-12-31T00:00:00Z");
        confirmed.setEndDatetime(null);
        Event planning = event(EventStatus.pending, "2026-12-31T00:00:00Z");
        planning.setEndDatetime(null);

        assertThat(resolver.forEvent(confirmed).code()).isEqualTo("CONFIRMED");
        assertThat(resolver.forEvent(planning).datePassed()).isFalse();
    }

    @Test
    @DisplayName("EC-NEW2 AC14, AC29 (boundary): a cancelled event in the past is not flagged as Date passed")
    void aPastCancelledEventIsNotFlagged() {
        assertThat(resolver.forEvent(event(EventStatus.cancelled, "2026-12-31T00:00:00Z")).datePassed()).isFalse();
    }
}

