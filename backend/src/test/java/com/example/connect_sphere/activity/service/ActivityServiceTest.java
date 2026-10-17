package com.example.connect_sphere.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.entity.RequestActivity;
import com.example.connect_sphere.activity.repository.RequestActivityRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EC-NEW5: how the change log is read. Normal or Major is decided once, here
 * on the server; the expected values come from EC-NEW5's ACs and Week 4's
 * Event Information Management ("distinguish between information that may be
 * edited normally and important changes") and Event Change Requests ("date,
 * time, expected attendance, venue requirements, or equipment
 * requirements"). Repositories are stubs.
 */
@Tag("unit")
@Tag("EC-NEW5")
class ActivityServiceTest {

    private final RequestActivityRepository repository = mock(RequestActivityRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final ActivityService service = new ActivityService(repository, mock(UserRepository.class), events);
    private final UUID requestId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final List<RequestActivity> rows = new ArrayList<>();

    private RequestActivity row(ActivityType type, Map<String, Object> values) {
        RequestActivity a = new RequestActivity();
        a.setActivityId(UUID.randomUUID());
        a.setRequestId(requestId);
        a.setActivityType(type);
        a.setActorName("ec1");
        a.setActorRole("ec");
        a.setFlaggedFields(List.of());
        a.setOccurredAt(OffsetDateTime.parse("2026-10-01T00:00:00Z").plusHours(rows.size()));
        a.setFieldValues(values);
        rows.add(a);
        return a;
    }

    private List<ActivityDto> timeline(UserRole role) {
        when(repository.findVisible(eq(requestId), any())).thenReturn(rows);
        return service.timeline(requestId, role);
    }

    private static Map<String, Object> values() {
        Map<String, Object> v = new HashMap<>();
        v.put("eventName", "Town Hall");
        v.put("purpose", "Update");
        v.put("startDatetime", "2027-03-10T09:00:00+08:00");
        v.put("endDatetime", "2027-03-10T12:00:00+08:00");
        v.put("expectedAttendance", 150);
        v.put("venueRequirements", "Theatre");
        v.put("equipmentRequirements", "Mics");
        v.put("accessibilityNeeds", List.of("step_free_access", "elevators"));
        return v;
    }

    @ParameterizedTest
    @EnumSource(value = ActivityType.class, names = {"coordinator_reassigned", "venue_unavailable", "venue_booking_rejected",
            "venue_booking_cancelled", "venue_added", "safety_rejected", "safety_changes_requested", "returned_to_planning"})
    @DisplayName("EC-NEW5 AC17, AC18 (happy): reassignment, venue loss, rejected/cancelled bookings, an added venue, safety rejections or changes and returning to Planning are Major")
    void theListedKindsAreAlwaysMajor(ActivityType type) {
        row(type, null);
        assertThat(timeline(UserRole.ec)).singleElement().extracting(ActivityDto::significance).isEqualTo("major");
    }

    @ParameterizedTest
    @EnumSource(value = ActivityType.class, names = {"submitted", "coordinator_assigned", "clarification_requested",
            "approved", "rejected", "venue_booking_requested", "venue_booking_approved", "venue_booking_replaced",
            "venue_alternative_suggested", "equipment_requested", "equipment_approved", "equipment_rejected",
            "sent_for_safety_review", "safety_review_withdrawn", "safety_approved", "event_confirmed"})
    @DisplayName("EC-NEW5 AC17, AC19 (happy): every other kind of entry is Normal, and each is labelled")
    void everyOtherKindIsNormal(ActivityType type) {
        row(type, null);
        assertThat(timeline(UserRole.ec)).singleElement().extracting(ActivityDto::significance).isEqualTo("normal");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"startDatetime", "endDatetime", "expectedAttendance",
            "venueRequirements", "equipmentRequirements"})
    @DisplayName("EC-NEW5 AC7, AC18 (boundary): an organiser's resubmission that changes date, time, attendance, venue or equipment requirements is Major (Week 4)")
    void resubmittingAnImportantChangeIsMajor(String field) {
        row(ActivityType.clarification_requested, values());
        Map<String, Object> after = values();
        after.put(field, field.endsWith("Datetime") ? "2027-03-11T09:00:00+08:00"
                : field.equals("expectedAttendance") ? 151 : "Something else");
        row(ActivityType.clarification_responded, after);

        assertThat(timeline(UserRole.ec).get(1).significance()).isEqualTo("major");
    }

    @Test
    @DisplayName("EC-NEW5 AC19 (boundary): a resubmission that changes only the name, purpose or accessibility is Normal")
    void resubmittingOtherDetailsIsNormal() {
        row(ActivityType.clarification_requested, values());
        Map<String, Object> after = values();
        after.put("eventName", "Town Hall 2027");
        after.put("purpose", "Quarterly update");
        after.put("accessibilityNeeds", List.of("step_free_access"));
        row(ActivityType.clarification_responded, after);

        assertThat(timeline(UserRole.ec).get(1).significance()).isEqualTo("normal");
    }

    @Test
    @DisplayName("EC-NEW5 AC23 (boundary): the same moment in another time-zone notation, or the same list in another order, is no change")
    void notationAndOrderAreNotChanges() {
        row(ActivityType.clarification_requested, values());
        Map<String, Object> after = values();
        after.put("startDatetime", "2027-03-10T01:00:00Z");
        after.put("accessibilityNeeds", List.of("elevators", "step_free_access"));
        after.put("expectedAttendance", 150.0);
        row(ActivityType.clarification_responded, after);

        assertThat(timeline(UserRole.ec).get(1).significance()).isEqualTo("normal");
        assertThat(ActivityService.sameValue("2027-03-10T09:00:00+08:00", "2027-03-10T01:00:00Z")).isTrue();
        assertThat(ActivityService.sameValue(List.of("a", "b"), List.of("b", "a"))).isTrue();
        assertThat(ActivityService.sameValue("", null)).isTrue();
        assertThat(ActivityService.sameValue("Theatre", "Banquet")).isFalse();
    }

    @Test
    @DisplayName("EC-NEW5 AC1, AC14 (happy): a confirmed event whose end has passed shows Completed at its end time, after every other entry")
    void aFinishedEventShowsCompleted() {
        row(ActivityType.event_confirmed, null).setEventId(eventId);
        Event event = new Event();
        event.setEventId(eventId);
        event.setStatus(EventStatus.confirmed);
        event.setEndDatetime(OffsetDateTime.parse("2026-01-01T04:00:00Z"));
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        List<ActivityDto> log = timeline(UserRole.eo);

        assertThat(log).hasSize(2);
        assertThat(log.get(1).type()).isEqualTo("event_completed");
        assertThat(log.get(1).occurredAt()).isEqualTo(event.getEndDatetime());
        assertThat(log.get(1).toStatus()).isEqualTo("COMPLETED");
        assertThat(log.get(1).activityId()).isNull();
    }

    @Test
    @DisplayName("EC-NEW5 AC14 (boundary): an event still in the future, or not confirmed, shows no Completed entry")
    void noCompletedEntryBeforeTheEnd() {
        row(ActivityType.approved, null).setEventId(eventId);
        Event event = new Event();
        event.setEventId(eventId);
        event.setStatus(EventStatus.confirmed);
        event.setEndDatetime(OffsetDateTime.now().plusDays(3));
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        assertThat(timeline(UserRole.ec)).extracting(ActivityDto::type).containsExactly("approved");
        event.setEndDatetime(OffsetDateTime.parse("2026-01-01T04:00:00Z"));
        event.setStatus(EventStatus.pending);
        assertThat(timeline(UserRole.ec)).extracting(ActivityDto::type).containsExactly("approved");
    }

    @Test
    @DisplayName("EC-NEW5 AC31 (negative): a role outside an entry's audience, such as Venue Staff for Completed, is not shown it")
    void completedFollowsItsAudience() {
        row(ActivityType.event_confirmed, null).setEventId(eventId);
        assertThat(ActivityType.event_completed.audience()).doesNotContain(UserRole.vs);
        assertThat(timeline(UserRole.vs)).extracting(ActivityDto::type).containsExactly("event_confirmed");
    }

    @Test
    @DisplayName("EC-NEW5 AC31 (negative): organisers are never in the audience of internal venue, equipment or safety-review entries")
    void internalEntriesAreNotForOrganisers() {
        for (ActivityType type : List.of(ActivityType.venue_booking_requested, ActivityType.venue_booking_approved,
                ActivityType.venue_booking_rejected, ActivityType.venue_alternative_suggested, ActivityType.venue_unavailable,
                ActivityType.venue_added, ActivityType.equipment_requested, ActivityType.equipment_rejected,
                ActivityType.sent_for_safety_review, ActivityType.safety_changes_requested)) {
            assertThat(type.audience()).as(type.name()).doesNotContain(UserRole.eo);
        }
    }
}
