package com.example.connect_sphere.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
class ReadinessServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SAFETY_OFFICER_ID = UUID.randomUUID();

    @Mock private EventRepository events;
    @Mock private ReadinessItemStateRepository itemStates;
    @Mock private EventSafetyReviewRepository reviews;
    @Mock private UserRepository users;
    @Mock private EventRequestRepository eventRequests;
    @Mock private NotificationService notifications;

    private ReadinessService service;
    private Event event;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReadinessService(events, itemStates, reviews, users, eventRequests, notifications);
        event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Town Hall");
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.pending);
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(itemStates.findByEventId(EVENT_ID)).thenReturn(List.of());
        when(itemStates.findByEventIdAndItemKey(any(), any())).thenReturn(Optional.empty());
        when(reviews.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(itemStates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviews.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void checklistHasStandardItemsAndStartsIncomplete() {
        ReadinessDto result = service.forSafetyOfficer(EVENT_ID);

        assertThat(result.totalItems()).isEqualTo(6);
        assertThat(result.completedItems()).isZero();
        assertThat(result.items()).extracting(ReadinessItemDto::itemKey)
                .containsExactly("venue_booking", "capacity_facilities", "accessibility",
                        "equipment", "registration", "safety_operations");
        assertThat(result.approvalStatus()).isEqualTo("not_reviewed");
    }

    @Test
    void updatingAnItemPersistsTheActorAndTimestamp() {
        service.updateItem(SAFETY_OFFICER_ID, EVENT_ID, "venue_booking",
                new UpdateReadinessItemRequest(true, "Venue booking approved"));

        ArgumentCaptor<ReadinessItemState> state = ArgumentCaptor.forClass(ReadinessItemState.class);
        verify(itemStates).save(state.capture());
        assertThat(state.getValue().isChecked()).isTrue();
        assertThat(state.getValue().getNote()).isEqualTo("Venue booking approved");
        assertThat(state.getValue().getUpdatedBy()).isEqualTo(SAFETY_OFFICER_ID);
        assertThat(state.getValue().getUpdatedAt()).isNotNull();
        assertThat(state.getValue().getCheckedBy()).isEqualTo(SAFETY_OFFICER_ID);
        assertThat(state.getValue().getCheckedAt()).isNotNull();
    }

    @Test
    void incompleteChecklistBlocksApprovalAndListsOutstandingItems() {
        assertThatThrownBy(() -> service.review(
                SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("approve", "Ready")))
                .isInstanceOf(IncompleteReadinessException.class)
                .hasMessageContaining("Venue booking is approved and suitable")
                .hasMessageContaining("Safety, emergency, and on-site operational arrangements are ready");

        assertThat(event.getStatus()).isEqualTo(com.example.connect_sphere.event.entity.EventStatus.pending);
        verify(events, never()).save(any());
        verify(reviews, never()).save(any());
        verify(notifications, never()).createStatusChangeNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalConfirmsTheEventOnlyAfterEveryStandardItemIsChecked() {
        OffsetDateTime checkedAt = OffsetDateTime.now().minusMinutes(1);
        List<ReadinessItemState> checkedItems = List.of(
                new ReadinessItemState(EVENT_ID, "venue_booking", true, "", SAFETY_OFFICER_ID, checkedAt),
                new ReadinessItemState(EVENT_ID, "capacity_facilities", true, "", SAFETY_OFFICER_ID, checkedAt),
                new ReadinessItemState(EVENT_ID, "accessibility", true, "", SAFETY_OFFICER_ID, checkedAt),
                new ReadinessItemState(EVENT_ID, "equipment", true, "", SAFETY_OFFICER_ID, checkedAt),
                new ReadinessItemState(EVENT_ID, "registration", true, "", SAFETY_OFFICER_ID, checkedAt),
                new ReadinessItemState(EVENT_ID, "safety_operations", true, "", SAFETY_OFFICER_ID, checkedAt));
        when(itemStates.findByEventId(EVENT_ID)).thenReturn(checkedItems);
        EventSafetyReview savedReview = new EventSafetyReview(
                EVENT_ID, "approved", "Ready to proceed", SAFETY_OFFICER_ID, OffsetDateTime.now());
        when(reviews.findByEventId(EVENT_ID)).thenReturn(Optional.empty(), Optional.of(savedReview));

        ReadinessDto result = service.review(
                SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("approve", "Ready to proceed"));

        assertThat(event.getStatus()).isEqualTo(com.example.connect_sphere.event.entity.EventStatus.confirmed);
        assertThat(result.approvalStatus()).isEqualTo("approved");
        assertThat(result.approvalNote()).isEqualTo("Ready to proceed");
        assertThat(result.completedItems()).isEqualTo(result.totalItems());
        verify(events).save(event);
        verify(reviews).save(any());
    }

    @Test
    void uncheckingAnItemKeepsTheLastCheckerAndCheckTime() {
        OffsetDateTime checkedAt = OffsetDateTime.now().minusDays(1);
        ReadinessItemState state = new ReadinessItemState(
                EVENT_ID, "venue_booking", true, "", SAFETY_OFFICER_ID, checkedAt);

        state.update(false, "Needs rechecking", UUID.randomUUID(), OffsetDateTime.now());

        assertThat(state.isChecked()).isFalse();
        assertThat(state.getCheckedBy()).isEqualTo(SAFETY_OFFICER_ID);
        assertThat(state.getCheckedAt()).isEqualTo(checkedAt);
    }

    @Test
    void coordinatorCanOnlyReadTheirOwnEventsReadiness() {
        event.setCoordinatorId(UUID.randomUUID());

        assertThatThrownBy(() -> service.forCoordinator(UUID.randomUUID(), EVENT_ID))
                .isInstanceOf(com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException.class);
    }
}
