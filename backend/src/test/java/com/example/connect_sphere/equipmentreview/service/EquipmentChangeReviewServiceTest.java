package com.example.connect_sphere.equipmentreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.connect_sphere.equipmentreview.entity.EquipmentChangeReview;
import com.example.connect_sphere.equipmentreview.entity.ReviewOutcome;
import com.example.connect_sphere.equipmentreview.repository.EquipmentChangeReviewRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.equipment.dto.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.service.EquipmentReservationService;
import com.example.connect_sphere.equipmentreview.dto.EquipmentChangeReviewDto.OutcomeRequest;
import com.example.connect_sphere.equipmentrequest.dto.SubmitEquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.repository.EquipmentRequestRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
@Tag("TS11")
class EquipmentChangeReviewServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");

    private final EquipmentChangeReviewRepository reviews = mock(EquipmentChangeReviewRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EquipmentReservationService reservations = mock(EquipmentReservationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EquipmentChangeReviewService service = new EquipmentChangeReviewService(reviews, events,
            reservations, users, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID reviewer = UUID.randomUUID();
    private final UUID coordinator = UUID.randomUUID();
    private final UUID logId = UUID.randomUUID();
    private EquipmentChangeReview review;
    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Gala");
        event.setCoordinatorId(coordinator);
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T10:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T13:00+08:00"));
        review = new EquipmentChangeReview();
        review.setReviewId(UUID.randomUUID());
        review.setEventId(event.getEventId());
        review.setChangeRequestId(UUID.randomUUID());
        review.setChangedAt(OffsetDateTime.parse("2027-02-28T10:00+08:00"));
        review.setPreviousValues(Map.of("startDatetime", "2027-03-10T09:00+08:00"));
        review.setUpdatedValues(Map.of("startDatetime", "2027-03-10T10:00+08:00"));
        review.setAffectedReservations(List.of(Map.of("logId", logId.toString(), "equipmentName", "Mic",
                "quantity", 2, "loanedFrom", "2027-03-10T01:00:00Z", "loanedUntil", "2027-03-10T04:00:00Z")));
        when(reviews.findForUpdate(review.getReviewId())).thenReturn(Optional.of(review));
        when(reviews.findById(review.getReviewId())).thenReturn(Optional.of(review));
        when(reviews.save(any())).thenAnswer(i -> i.getArgument(0));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        when(reservations.reservationsForEvent(event.getEventId())).thenReturn(List.of(new EquipmentReservationResponse(
                logId, event.getEventId(), UUID.randomUUID(), "Mic", 2, null,
                Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T04:00:00Z"), null, false)));
    }

    /** The reservation re-booked to span the event's 10:00-13:00, so "still suitable" is allowed. */
    private void reservationCoversTheEvent() {
        when(reservations.reservationsForEvent(event.getEventId())).thenReturn(List.of(new EquipmentReservationResponse(
                logId, event.getEventId(), UUID.randomUUID(), "Mic", 2, null,
                Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T05:00:00Z"), null, false)));
    }

    private void refused(OutcomeRequest request, HttpStatus status) {
        assertThatThrownBy(() -> service.recordOutcome(reviewer, review.getReviewId(), request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
        verify(reviews, never()).save(any());
    }

    @Test
    @DisplayName("TS11 AC11, AC14 (happy): recording 'still suitable' saves who and when, and clears the flag")
    void stillSuitable() {
        reservationCoversTheEvent();
        var detail = service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("still_suitable", null));

        assertThat(review.getOutcome()).isEqualTo(ReviewOutcome.still_suitable);
        assertThat(review.getReviewedBy()).isEqualTo(reviewer);
        assertThat(review.getReviewedAt().toInstant()).isEqualTo(NOW);
        assertThat(detail.needsReview()).isFalse();
        assertThat(detail.reservations()).singleElement().satisfies(r -> assertThat(r.flagged()).isFalse());
    }

    @Test
    @DisplayName("TS11 AC10 (happy): while unreviewed, the reservation that no longer covered the event is flagged")
    void unreviewedIsFlagged() {
        var detail = service.detail(review.getReviewId());

        assertThat(detail.needsReview()).isTrue();
        assertThat(detail.reservations()).singleElement().satisfies(r -> assertThat(r.flagged()).isTrue());
    }

    @Test
    @DisplayName("TS11 AC11 (negative): 'needs coordinator action' without a note is refused and nothing is saved")
    void coordinatorActionNeedsANote() {
        refused(new OutcomeRequest("needs_coordinator_action", "  "), HttpStatus.BAD_REQUEST);
        assertThat(review.needsReview()).isTrue();
    }

    @Test
    @DisplayName("TS11 AC11 (boundary): a note of 2,000 characters is accepted; 2,001 is refused")
    void noteLength() {
        refused(new OutcomeRequest("needs_coordinator_action", "x".repeat(2001)), HttpStatus.BAD_REQUEST);

        service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("needs_coordinator_action", "x".repeat(2000)));
        assertThat(review.getOutcomeNote()).hasSize(2000);
    }

    @Test
    @DisplayName("TS11 AC11 (negative): a missing or unknown outcome is refused")
    void outcomeIsRequired() {
        refused(new OutcomeRequest(null, null), HttpStatus.BAD_REQUEST);
        refused(new OutcomeRequest("approved", null), HttpStatus.BAD_REQUEST);
        refused(null, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("TS11 AC14 (conflict): an already reviewed change cannot be reviewed again")
    void reviewedOnce() {
        review.setOutcome(ReviewOutcome.still_suitable);
        review.setReviewedBy(UUID.randomUUID());
        review.setReviewedAt(OffsetDateTime.now());

        refused(new OutcomeRequest("needs_coordinator_action", "More mics"), HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("TS11 AC13 (negative): 'still suitable' is refused while a reservation misses the event's time, and nothing is saved")
    void stillSuitableIsRefusedWhileAReservationMissesTheEvent() {
        refused(new OutcomeRequest("still_suitable", null), HttpStatus.CONFLICT);

        assertThat(review.getOutcome()).isNull();
    }

    @Test
    @DisplayName("TS11 AC13 (cross-cutting): recording an outcome only reads reservations; it never reserves, releases or changes the event")
    void outcomeChangesNothingElse() {
        reservationCoversTheEvent();
        service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("still_suitable", null));

        verify(reservations, never()).reserve(any());
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("TS11 AC12 (negative): a coordinator not assigned to the event cannot read its outcomes; the Lead can")
    void coordinatorScope() {
        assertThatThrownBy(() -> service.forEvent(UUID.randomUUID(), false, event.getEventId()))
                .isInstanceOf(NotAssignedCoordinatorException.class);

        when(reviews.findByEventIdOrderByChangedAtDesc(event.getEventId())).thenReturn(List.of(review));
        assertThat(service.forEvent(coordinator, false, event.getEventId())).hasSize(1);
        assertThat(service.forEvent(UUID.randomUUID(), true, event.getEventId())).hasSize(1);
    }

    @Test
    @DisplayName("TS11 AC11 (negative): a blank outcome is refused and nothing is saved")
    void aBlankOutcomeIsRefused() {
        assertThatThrownBy(() -> service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("  ", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("Choose a review outcome."));
        verify(reviews, never()).save(any());
    }

    @Test
    @DisplayName("TS11 AC10 (negative): an unknown review is not found")
    void anUnknownReviewIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.detail(unknown))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("Equipment change review " + unknown + " not found");
                });
    }

    private EquipmentChangeReview reviewed(String changedAt, ReviewOutcome outcome) {
        EquipmentChangeReview other = new EquipmentChangeReview();
        other.setReviewId(UUID.randomUUID());
        other.setEventId(event.getEventId());
        other.setChangedAt(OffsetDateTime.parse(changedAt));
        other.setAffectedReservations(List.of());
        other.setOutcome(outcome);
        if (outcome != null) {
            other.setReviewedBy(reviewer);
            other.setReviewedAt(OffsetDateTime.parse(changedAt));
        }
        return other;
    }

    @Test
    @DisplayName("TS11 AC10 (happy): the queue lists changes still to review first, each group newest first")
    void theQueueListsOpenReviewsFirst() {
        EquipmentChangeReview newerReviewed = reviewed("2027-03-01T10:00+08:00", ReviewOutcome.still_suitable);
        EquipmentChangeReview olderOpen = reviewed("2027-02-01T10:00+08:00", null);
        when(reviews.findAllByOrderByChangedAtDesc()).thenReturn(List.of(newerReviewed, olderOpen));

        assertThat(service.queue()).extracting(s -> s.reviewId())
                .containsExactly(olderOpen.getReviewId(), newerReviewed.getReviewId());
    }

    @Test
    @DisplayName("TS11 AC13 (boundary): still suitable on an older change leaves the flag when the newest change needs the coordinator")
    void anOlderStillSuitableDoesNotOverrideTheNewest() {
        reservationCoversTheEvent();
        EquipmentRequestRepository requests = mock(EquipmentRequestRepository.class);
        service.setRequests(requests);
        EquipmentChangeReview newest = reviewed("2027-03-01T10:00+08:00", ReviewOutcome.needs_coordinator_action);
        when(reviews.findByEventIdOrderByChangedAtDesc(event.getEventId())).thenReturn(List.of(newest, review));

        service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("still_suitable", null));

        assertThat(review.getOutcome()).isEqualTo(ReviewOutcome.still_suitable);
        verify(requests, never()).findByEventIdForUpdate(any());
        verify(requests, never()).saveAll(any());
    }

    @Test
    @DisplayName("TS11 AC13 (boundary): with no flagged request, still suitable changes no request")
    void noFlaggedRequestMeansNothingToClear() {
        reservationCoversTheEvent();
        EquipmentRequestRepository requests = mock(EquipmentRequestRepository.class);
        service.setRequests(requests);
        when(reviews.findByEventIdOrderByChangedAtDesc(event.getEventId())).thenReturn(List.of(review));
        EquipmentRequest approved = new EquipmentRequest(UUID.randomUUID(), event.getEventId(),
                EquipmentRequestStatus.approved, "");
        when(requests.findByEventIdForUpdate(event.getEventId())).thenReturn(List.of(approved));

        service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("still_suitable", null));

        assertThat(approved.getStatus()).isEqualTo(EquipmentRequestStatus.approved);
        verify(requests, never()).saveAll(any());
    }

    @Test
    @DisplayName("TS11 AC13 (negative): still suitable is refused while the event's approved items are not all reserved for its time; once they are, it is accepted")
    void stillSuitableNeedsEveryApprovedItemReserved() {
        UUID mics = UUID.randomUUID();
        event.setEquipmentItems(List.of(new SubmitEquipmentRequest.Item(mics, 3)));
        when(reservations.reservationsForEvent(event.getEventId())).thenReturn(List.of(
                new EquipmentReservationResponse(logId, event.getEventId(), mics, "Mic", 2, null,
                        Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T05:00:00Z"), null, false),
                new EquipmentReservationResponse(UUID.randomUUID(), event.getEventId(), UUID.randomUUID(), "Cable", 5,
                        null, Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T05:00:00Z"), null, false)));

        refused(new OutcomeRequest("still_suitable", null), HttpStatus.CONFLICT);
        assertThat(review.getOutcome()).isNull();

        event.setEquipmentItems(List.of(new SubmitEquipmentRequest.Item(mics, 2)));
        service.recordOutcome(reviewer, review.getReviewId(), new OutcomeRequest("still_suitable", null));
        assertThat(review.getOutcome()).isEqualTo(ReviewOutcome.still_suitable);
    }
}
