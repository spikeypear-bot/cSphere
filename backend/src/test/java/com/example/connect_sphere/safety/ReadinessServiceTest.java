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
import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.event.service.EventLifecycleService.SafetyDecision;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("unit")
@Tag("SO-C3")
@Tag("SO-C4")
@Tag("SO-C5")
@Tag("SO-C6")
@Tag("EC08")
class ReadinessServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SAFETY_OFFICER_ID = UUID.randomUUID();

    @Mock private EventRepository events;
    @Mock private ReadinessItemStateRepository itemStates;
    @Mock private EventSafetyReviewRepository reviews;
    @Mock private UserRepository users;
    @Mock private EventRequestRepository eventRequests;
    @Mock private NotificationService notifications;
    @Mock private EventLifecycleService lifecycle;

    private ReadinessService service;
    private Event event;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReadinessService(events, itemStates, reviews, users, eventRequests, notifications, lifecycle,
                Clock.systemUTC());
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
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        assertThatThrownBy(() -> service.review(
                SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("approve", "Ready")))
                .isInstanceOf(IncompleteReadinessException.class)
                .hasMessageContaining("Venue booking is approved and suitable")
                .hasMessageContaining("Safety, emergency, and on-site operational arrangements are ready");

        assertThat(event.getStatus()).isEqualTo(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        verify(events, never()).save(any());
        verify(reviews, never()).save(any());
        verify(lifecycle, never()).recordSafetyDecision(any(), any(), any(), any(), any());
        verify(notifications, never()).createStatusChangeNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalRecordsTheSafetyApprovalOnlyAfterEveryStandardItemIsChecked() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
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

        // EC08: approval moves the event to Safety Approved (through the
        // lifecycle); the coordinator then confirms it.
        verify(lifecycle).recordSafetyDecision(SAFETY_OFFICER_ID, EVENT_ID, SafetyDecision.APPROVE, "Ready to proceed", List.of());
        assertThat(result.approvalStatus()).isEqualTo("approved");
        assertThat(result.approvalNote()).isEqualTo("Ready to proceed");
        assertThat(result.completedItems()).isEqualTo(result.totalItems());
        verify(reviews).save(any());
    }

    @Test
    void aDecisionIsRefusedUntilTheCoordinatorSendsTheEventForReview() {
        assertThatThrownBy(() -> service.review(
                SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("approve", "")))
                .isInstanceOf(InvalidReadinessUpdateException.class)
                .hasMessage("The Event Coordinator has not sent this event for its safety check yet.");
        verify(reviews, never()).save(any());
        verify(lifecycle, never()).recordSafetyDecision(any(), any(), any(), any(), any());
    }

    @Test
    void requestingChangesOrRejectingNeedsANoteAndSendsTheEventBackToTheCoordinator() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("reject", " ")))
                .isInstanceOf(InvalidReadinessUpdateException.class);
        verify(lifecycle, never()).recordSafetyDecision(any(), any(), any(), any(), any());

        ReadinessDto changes = service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("request_changes", "Add a second exit marshal", List.of("safety_operations")));
        verify(lifecycle).recordSafetyDecision(SAFETY_OFFICER_ID, EVENT_ID, SafetyDecision.REQUEST_CHANGES,
                "Add a second exit marshal", List.of("safety_operations"));
        service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("reject", "Crowd flow unsafe", List.of("capacity_facilities")));
        verify(lifecycle).recordSafetyDecision(SAFETY_OFFICER_ID, EVENT_ID, SafetyDecision.REJECT, "Crowd flow unsafe",
                List.of("capacity_facilities"));
        assertThat(changes.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void checklistCanBeWorkedOnWhileAwaitingReviewButNotOnceApproved() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        service.updateItem(SAFETY_OFFICER_ID, EVENT_ID, "venue_booking", new UpdateReadinessItemRequest(true, ""));

        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.safety_approved);
        assertThatThrownBy(() -> service.updateItem(SAFETY_OFFICER_ID, EVENT_ID, "venue_booking",
                new UpdateReadinessItemRequest(false, "")))
                .isInstanceOf(InvalidReadinessUpdateException.class);
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

    // ---- SO-C5 / SO-C6: the checklist items a decision concerns ----------------

    private static final List<String> ALL_ITEMS = List.of("venue_booking", "capacity_facilities", "accessibility",
            "equipment", "registration", "safety_operations");

    private void everyItemChecked() {
        OffsetDateTime at = OffsetDateTime.now().minusMinutes(1);
        when(itemStates.findByEventId(EVENT_ID)).thenReturn(ALL_ITEMS.stream()
                .map(key -> new ReadinessItemState(EVENT_ID, key, true, "", SAFETY_OFFICER_ID, at)).toList());
    }

    private void assertNothingDecided() {
        assertThat(event.getStatus()).isEqualTo(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        verify(reviews, never()).save(any());
        verify(lifecycle, never()).recordSafetyDecision(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SO-C5 AC5, SO-C6 AC5 (negative): a rejection or a request for changes that names no checklist item is refused, and nothing is decided")
    void aRejectionOrRequestNamingNoChecklistItemIsRefused() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("reject", "Crowd flow unsafe")))
                .isInstanceOf(InvalidReadinessUpdateException.class)
                .hasMessage("Select at least one checklist item the rejection concerns.");
        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("request_changes", "Add a marshal", List.of())))
                .isInstanceOf(InvalidReadinessUpdateException.class)
                .hasMessage("Select at least one checklist item that needs changing.");
        assertNothingDecided();
    }

    @Test
    @DisplayName("SO-C5 AC5, SO-C6 AC5 (negative): an item that is not on the checklist is refused, and nothing is decided")
    void anItemNotOnTheChecklistIsRefused() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);

        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("reject", "Unsafe", List.of("accessibility", "parking"))))
                .isInstanceOf(InvalidReadinessUpdateException.class)
                .hasMessage("Unknown checklist item(s): parking.");
        assertNothingDecided();
    }

    @Test
    @DisplayName("SO-C5 AC5, SO-C6 AC5 (negative): an approval cannot name checklist items, and nothing is decided")
    void anApprovalCannotNameChecklistItems() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        everyItemChecked();

        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("approve", "", List.of("equipment"))))
                .isInstanceOf(InvalidReadinessUpdateException.class)
                .hasMessage("Checklist items are only selected when requesting changes or rejecting.");
        assertNothingDecided();
    }

    @Test
    @DisplayName("SO-C5 AC6, AC11, SO-C6 AC12 (happy): a rejection is allowed with every item ticked; its items are kept in checklist order, once each, on the review and the timeline")
    void theSelectedItemsAreKeptInChecklistOrderOnTheReviewAndTimeline() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        everyItemChecked();

        service.review(SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("reject", "  Layout blocks the exits  ",
                List.of("safety_operations", "accessibility", "safety_operations")));

        ArgumentCaptor<EventSafetyReview> saved = ArgumentCaptor.forClass(EventSafetyReview.class);
        verify(reviews).save(saved.capture());
        assertThat(saved.getValue().getReviewStatus()).isEqualTo("rejected");
        assertThat(saved.getValue().getNote()).isEqualTo("Layout blocks the exits");
        assertThat(saved.getValue().getFlaggedItems()).containsExactly("accessibility", "safety_operations");
        verify(lifecycle).recordSafetyDecision(SAFETY_OFFICER_ID, EVENT_ID, SafetyDecision.REJECT,
                "Layout blocks the exits", List.of("accessibility", "safety_operations"));
    }

    @Test
    @DisplayName("SO-C5 AC10, SO-C6 AC11 (happy): the readiness view returns the latest decision's items")
    void theReadinessViewReturnsTheLatestDecisionsItems() {
        EventSafetyReview review = new EventSafetyReview(EVENT_ID, "changes_requested", "Add a marshal",
                SAFETY_OFFICER_ID, OffsetDateTime.now());
        review.update("changes_requested", "Add a marshal", List.of("safety_operations"), SAFETY_OFFICER_ID,
                OffsetDateTime.now());
        when(reviews.findByEventId(EVENT_ID)).thenReturn(Optional.of(review));

        ReadinessDto view = service.forSafetyOfficer(EVENT_ID);

        assertThat(view.approvalStatus()).isEqualTo("changes_requested");
        assertThat(view.approvalNote()).isEqualTo("Add a marshal");
        assertThat(view.flaggedItems()).containsExactly("safety_operations");
    }

    @Test
    @DisplayName("SO-C5 AC3, AC4, SO-C6 AC3, AC4 (boundary): a blank reason is refused; 2,000 characters after trimming is accepted, 2,001 is refused")
    void theReasonIsRequiredAndAtMost2000Characters() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.awaiting_safety_review);
        List<String> items = List.of("equipment");

        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("request_changes", "   ", items)))
                .isInstanceOf(InvalidReadinessUpdateException.class);
        assertThatThrownBy(() -> service.review(SAFETY_OFFICER_ID, EVENT_ID,
                new SafetyApprovalRequest("reject", "x".repeat(2001), items)))
                .isInstanceOf(InvalidReadinessUpdateException.class);
        assertNothingDecided();

        service.review(SAFETY_OFFICER_ID, EVENT_ID, new SafetyApprovalRequest("reject", " " + "x".repeat(2000) + " ", items));
        verify(lifecycle).recordSafetyDecision(SAFETY_OFFICER_ID, EVENT_ID, SafetyDecision.REJECT, "x".repeat(2000), items);
    }
}
