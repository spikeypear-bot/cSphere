package com.example.connect_sphere.equipmentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.connect_sphere.changerequest.ChangeApproved;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.equipment.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestSubmitted;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/** Unit tests for the content this service builds ({@code describeRequest},
 * {@code openReview}) — the after-commit listener wiring itself is covered
 * end to end by {@link TechnicalSupportNotificationFlowTest}. */
@Tag("unit")
@Tag("TS11")
class TechnicalSupportNotificationServiceTest {

    private final EventRepository events = mock(EventRepository.class);
    private final EquipmentRequestRepository requests = mock(EquipmentRequestRepository.class);
    private final EquipmentReservationService reservations = mock(EquipmentReservationService.class);
    private final EquipmentChangeReviewRepository reviews = mock(EquipmentChangeReviewRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TechnicalSupportNotificationService service = new TechnicalSupportNotificationService(events,
            requests, mock(EquipmentRequestLineRepository.class), mock(EquipmentRepository.class), reservations,
            reviews, users, mock(NotificationService.class), null);

    private Event event;
    private ChangeApproved approved;

    @BeforeEach
    void setUp() {
        UUID coordinator = UUID.randomUUID();
        event = new Event();
        event.setEventId(UUID.randomUUID());
        event.setEventName("Gala");
        event.setCoordinatorId(coordinator);
        User ec1 = new User();
        ec1.setUsername("ec1");
        when(users.findById(coordinator)).thenReturn(Optional.of(ec1));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        approved = new ChangeApproved(UUID.randomUUID(), event.getEventId(),
                Map.of("startDatetime", "2027-03-10T09:00+08:00", "endDatetime", "2027-03-10T12:00+08:00",
                        "expectedAttendance", 150),
                Map.of("startDatetime", "2027-03-10T10:00+08:00", "endDatetime", "2027-03-10T13:00+08:00",
                        "expectedAttendance", 150),
                Set.of("startDatetime", "endDatetime"), OffsetDateTime.parse("2027-02-28T10:30+08:00"));
        when(reservations.reservationsForEvent(event.getEventId())).thenReturn(List.of(new EquipmentReservationResponse(
                UUID.randomUUID(), event.getEventId(), UUID.randomUUID(), "Projector", 1, "PJ-001",
                Instant.parse("2027-03-10T01:00:00Z"), Instant.parse("2027-03-10T04:00:00Z"), null, false)));
    }

    private void request(EquipmentRequestStatus status) {
        when(requests.findByEventId(event.getEventId())).thenReturn(List.of(
                new EquipmentRequest(UUID.randomUUID(), event.getEventId(), status, "")));
    }

    /** A processing request for the event, 10 Mar 2027 9:00-12:00 Singapore, with no itemised lines. */
    private UUID submitted() {
        event.setStartDatetime(OffsetDateTime.parse("2027-03-10T09:00+08:00"));
        event.setEndDatetime(OffsetDateTime.parse("2027-03-10T12:00+08:00"));
        UUID requestId = UUID.randomUUID();
        when(requests.findById(requestId)).thenReturn(Optional.of(
                new EquipmentRequest(requestId, event.getEventId(), EquipmentRequestStatus.processing, "")));
        return requestId;
    }

    @Test
    @Tag("TS09")
    @DisplayName("TS11 AC2 (happy): an additional request for a TS09 shortfall is notified the same way, marked as a shortfall request naming its damage report")
    void aShortfallRequestIsMarked() {
        UUID requestId = submitted();
        UUID reportId = UUID.randomUUID();
        EquipmentDamageOutcomeRepository outcomes = mock(EquipmentDamageOutcomeRepository.class);
        when(outcomes.findByAdditionalRequestId(requestId))
                .thenReturn(Optional.of(new EquipmentDamageOutcome(reportId, event.getEventId())));
        service.setShortfalls(outcomes);

        var notice = service.describeRequest(requestId).orElseThrow();

        assertThat(notice.message()).isEqualTo("Shortfall request for damage report " + reportId + ". Gala, "
                + "10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM (Singapore time). Coordinator: ec1. No itemised equipment.");
        assertThat(notice.referenceId()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("TS11 AC2 (negative): a request not raised for a shortfall is not marked as one")
    void anOrdinaryRequestIsNotMarked() {
        UUID requestId = submitted();
        EquipmentDamageOutcomeRepository outcomes = mock(EquipmentDamageOutcomeRepository.class);
        when(outcomes.findByAdditionalRequestId(requestId)).thenReturn(Optional.empty());
        service.setShortfalls(outcomes);

        assertThat(service.describeRequest(requestId).orElseThrow().message()).isEqualTo("Gala, "
                + "10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM (Singapore time). Coordinator: ec1. No itemised equipment.");
    }

    @Test
    @DisplayName("TS11 AC8, AC9 (happy): the review keeps the old and new times and the uncovered reservation, and the notice says so")
    void opensAReview() {
        request(EquipmentRequestStatus.approved);

        var notice = service.openReview(approved).orElseThrow();

        ArgumentCaptor<EquipmentChangeReview> saved = ArgumentCaptor.forClass(EquipmentChangeReview.class);
        verify(reviews).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPreviousValues()).containsOnlyKeys("startDatetime", "endDatetime");
        assertThat(saved.getValue().getUpdatedValues()).containsEntry("startDatetime", "2027-03-10T10:00+08:00");
        assertThat(saved.getValue().getAffectedReservations()).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("serialNumber", "PJ-001"));
        assertThat(notice.message()).isEqualTo("An approved change to Gala on 28 Feb 2027, 10:30 AM affects its "
                + "equipment. Coordinator: ec1. Start: 10 Mar 2027, 9:00 AM → 10 Mar 2027, 10:00 AM; "
                + "End: 10 Mar 2027, 12:00 PM → 10 Mar 2027, 1:00 PM. No longer covering the event: "
                + "1 × Projector (PJ-001) reserved 10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM.");
    }

    @Test
    @DisplayName("TS11 AC4 (happy): a processing request counts as a live request")
    void processingCounts() {
        request(EquipmentRequestStatus.processing);

        assertThat(service.openReview(approved)).isPresent();
    }

    @Test
    @Tag("EC-NEW3")
    @DisplayName("TS11 AC4 (happy): a request an earlier change flagged 'attention' still counts as a live request, so a later change is reviewed too")
    void attentionCounts() {
        request(EquipmentRequestStatus.attention);

        assertThat(service.openReview(approved)).isPresent();
    }

    @Test
    @DisplayName("TS11 AC1, AC4 (failure): a notice or review that cannot be made is logged and never fails the saved request or approval")
    void aFailingNoticeNeverFailsTheCoordinatorsAction() {
        TechnicalSupportNotificationService self = mock(TechnicalSupportNotificationService.class);
        NotificationService notifications = mock(NotificationService.class);
        when(self.describeRequest(any())).thenThrow(new IllegalStateException("database down"));
        when(self.openReview(any())).thenThrow(new IllegalStateException("database down"));
        TechnicalSupportNotificationService listener = new TechnicalSupportNotificationService(events, requests,
                mock(EquipmentRequestLineRepository.class), mock(EquipmentRepository.class), reservations,
                reviews, users, notifications, self);

        assertThatCode(() -> listener.onEquipmentRequestSubmitted(
                new EquipmentRequestSubmitted(UUID.randomUUID(), event.getEventId()))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onChangeApproved(approved)).doesNotThrowAnyException();
        verify(notifications, never()).createEquipmentRequestSubmittedNotifications(any(), any(), any(), any(), any());
        verify(notifications, never()).createEquipmentChangeReviewNotifications(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TS11 AC7 (negative): an event whose only request was rejected is not reviewed and nothing is saved")
    void rejectedRequestIsSkipped() {
        request(EquipmentRequestStatus.rejected);

        assertThat(service.openReview(approved)).isEmpty();
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("TS11 AC7 (negative): an event with no equipment request is not reviewed")
    void noRequestIsSkipped() {
        when(requests.findByEventId(event.getEventId())).thenReturn(List.of());

        assertThat(service.openReview(approved)).isEmpty();
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("TS11 AC15 (conflict): a change that already has a review opens no second one")
    void alreadyReviewedIsSkipped() {
        request(EquipmentRequestStatus.approved);
        when(reviews.existsByChangeRequestId(approved.changeRequestId())).thenReturn(true);

        assertThat(service.openReview(approved)).isEmpty();
        verify(reviews, never()).saveAndFlush(any());
    }
}
