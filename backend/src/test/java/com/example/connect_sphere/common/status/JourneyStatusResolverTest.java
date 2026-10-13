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
class JourneyStatusResolverTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private final EventRepository events = mock(EventRepository.class);
    private final JourneyStatusResolver resolver = new JourneyStatusResolver(events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aSubmittedRequestWithNoCoordinatorIsSubmittedAndWaitsForTheLead() {
        StatusView status = resolver.forRequest(request(EventRequestStatus.pending, null));

        assertThat(status.code()).isEqualTo("SUBMITTED");
        assertThat(status.nextActor()).isEqualTo("Event Coordinator Lead");
    }

    @Test
    void aSubmittedRequestWithACoordinatorIsUnderReview() {
        assertThat(resolver.forRequest(request(EventRequestStatus.pending, UUID.randomUUID())).code())
                .isEqualTo("UNDER_REVIEW");
    }

    @ParameterizedTest
    @CsvSource({"draft,DRAFT", "clarification_required,CLARIFICATION_REQUIRED", "rejected,REJECTED",
            "cancelled,CANCELLED"})
    void otherRequestStatusesMapOneToOne(EventRequestStatus stored, String expected) {
        assertThat(resolver.forRequest(request(stored, UUID.randomUUID())).code()).isEqualTo(expected);
    }

    @Test
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
    void eventStatusesMapOneToOne(EventStatus stored, String expected) {
        assertThat(resolver.forEvent(event(stored, "2027-03-10T04:00:00Z")).code()).isEqualTo(expected);
    }

    @Test
    void aConfirmedEventBecomesCompletedOnceItsEndHasPassed() {
        assertThat(resolver.forEvent(event(EventStatus.confirmed, "2026-12-31T23:59:59Z")).code())
                .isEqualTo("COMPLETED");
    }

    @Test
    void aConfirmedEventEndingExactlyNowIsStillConfirmed() {
        assertThat(resolver.forEvent(event(EventStatus.confirmed, "2027-01-01T00:00:00Z")).code())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void anUnconfirmedEventWhoseDateHasPassedIsFlaggedButKeepsItsStatus() {
        StatusView status = resolver.forEvent(event(EventStatus.pending, "2026-12-31T00:00:00Z"));

        assertThat(status.code()).isEqualTo("PLANNING");
        assertThat(status.datePassed()).isTrue();
    }

    @Test
    void finalStatusesOfferNoNextActor() {
        StatusView status = resolver.forEvent(event(EventStatus.cancelled, "2027-03-10T04:00:00Z"));

        assertThat(status.isFinal()).isTrue();
        assertThat(status.nextActor()).isNull();
    }

    @Test
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
}
