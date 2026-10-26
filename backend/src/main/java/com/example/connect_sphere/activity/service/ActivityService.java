package com.example.connect_sphere.activity.service;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.activity.dto.ActivityDto;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.entity.RequestActivity;
import com.example.connect_sphere.activity.repository.RequestActivityRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.entity.EventStatus;

/**
 * Writes and reads the request timeline.
 *
 * <p>{@link #record} is {@code MANDATORY}. Notification delivery work is also
 * saved transactionally, while actual in-app delivery retries after commit. A timeline
 * entry is part of the action: EC01/EC02 say a failed clarification or
 * review "does not change the status or create a history record". Joining
 * the caller's transaction makes both commit together or neither does, and
 * MANDATORY makes calling it without one a loud error rather than a silently
 * non-atomic write.
 */
@Service
public class ActivityService {

    private final RequestActivityRepository repository;
    private final UserRepository userRepository;
    private final EventRepository events;

    public ActivityService(RequestActivityRepository repository, UserRepository userRepository,
            EventRepository events) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.events = events;
    }

    /** Week 4 (Event Change Requests): the information whose change "may require
     * existing arrangements to be reconsidered". A change to any of these is Major. */
    static final List<String> IMPORTANT_FIELDS = List.of(
            "startDatetime", "endDatetime", "expectedAttendance", "venueRequirements", "equipmentRequirements");

    /** What a caller says happened. {@code message}/{@code flaggedFields}/
     * statuses are optional per type; {@code eventId} is set once an Event
     * exists. */
    public record Entry(
            UUID requestId,
            UUID eventId,
            ActivityType type,
            UUID actorUserId,
            String message,
            List<String> flaggedFields,
            String fromStatus,
            String toStatus,
            Map<String, String> fieldQuestions,
            Map<String, Object> fieldValues) {

        /** An entry with no per-field questions or captured values. */
        public Entry(UUID requestId, UUID eventId, ActivityType type, UUID actorUserId, String message,
                List<String> flaggedFields, String fromStatus, String toStatus) {
            this(requestId, eventId, type, actorUserId, message, flaggedFields, fromStatus, toStatus, null, null);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry entry) {
        User actor = userRepository.findById(entry.actorUserId())
                .orElseThrow(() -> new IllegalStateException("Unknown actor " + entry.actorUserId()));
        RequestActivity row = new RequestActivity();
        row.setActivityId(UUID.randomUUID());
        row.setRequestId(entry.requestId());
        row.setEventId(entry.eventId());
        row.setActivityType(entry.type());
        row.setActorUserId(actor.getUserId());
        row.setActorRole(actor.getRole().name());
        row.setActorName(actor.getUsername());
        if (entry.type() == ActivityType.venue_hold_expired) {
            // Keep the initiating user FK for traceability, but identify the automated actor accurately.
            row.setActorRole("system");
            row.setActorName("ConnectSphere");
        }
        row.setMessage(entry.message());
        row.setFlaggedFields(entry.flaggedFields() == null ? List.of() : List.copyOf(entry.flaggedFields()));
        row.setFromStatus(entry.fromStatus());
        row.setToStatus(entry.toStatus());
        row.setAudienceRoles(entry.type().audience().stream().map(Enum::name).sorted().toList());
        row.setOccurredAt(OffsetDateTime.now());
        row.setFieldQuestions(entry.fieldQuestions());
        row.setFieldValues(entry.fieldValues());
        repository.save(row);
    }

    /** Entries of one request that {@code viewerRole} may see, oldest first.
     * Callers must already have checked the viewer may see the request at
     * all; this only narrows which entries. */
    @Transactional(readOnly = true)
    public List<ActivityDto> timeline(UUID requestId, UserRole viewerRole) {
        List<RequestActivity> rows = repository.findVisible(requestId, viewerRole.name());
        List<ActivityDto> entries = new java.util.ArrayList<>();
        Map<String, Object> lastCaptured = null;
        UUID eventId = null;
        for (RequestActivity a : rows) {
            boolean changeEntry = CHANGE_ENTRIES.contains(a.getActivityType());
            boolean major = a.getActivityType().major()
                    || (a.getActivityType() == ActivityType.clarification_responded
                            && changesImportantField(lastCaptured, a.getFieldValues()))
                    || (changeEntry && touchesImportantField(a.getFieldValues()));
            // Change entries carry only the changed fields, so they never stand in
            // for the request's full values that a later reply is compared with.
            if (a.getFieldValues() != null && !changeEntry) {
                lastCaptured = a.getFieldValues();
            }
            if (a.getEventId() != null) {
                eventId = a.getEventId();
            }
            entries.add(new ActivityDto(
                    a.getActivityId(), a.getActivityType().name(), a.getActorName(), a.getActorRole(),
                    a.getMessage(), List.copyOf(a.getFlaggedFields()), a.getFromStatus(), a.getToStatus(),
                    a.getOccurredAt(), a.getFieldQuestions(), a.getFieldValues(), major ? "major" : "normal"));
        }
        completedEntry(eventId, viewerRole).ifPresent(entries::add);
        return entries;
    }

    /** EC-NEW5/EC-NEW2: a confirmed event whose end has passed is Completed. No
     * one does that, so it is not stored; it is shown at the end time. */
    private java.util.Optional<ActivityDto> completedEntry(UUID eventId, UserRole viewerRole) {
        if (eventId == null || !ActivityType.event_completed.audience().contains(viewerRole)) {
            return java.util.Optional.empty();
        }
        return events.findById(eventId)
                .filter(e -> (e.getStatus() == EventStatus.confirmed || e.getStatus() == EventStatus.completed)
                        && e.getEndDatetime() != null && e.getEndDatetime().isBefore(OffsetDateTime.now()))
                .map(e -> new ActivityDto(null, ActivityType.event_completed.name(), "ConnectSphere", null,
                        "The event took place and is now Completed.", List.of(), "CONFIRMED", "COMPLETED",
                        e.getEndDatetime(), null, null, "normal"));
    }

    /** Compared by meaning, not spelling (EC-NEW5): the same instant written
     * with another offset, or the same list in another order, is no change. */
    static boolean changesImportantField(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) {
            return false;
        }
        for (String field : IMPORTANT_FIELDS) {
            if (!sameValue(before.get(field), after.get(field))) {
                return true;
            }
        }
        return false;
    }

    /** EO03/EC-NEW3: a requested or approved change is Major when it changes
     * one of Week 4's important fields; its entry carries the changed fields. */
    private static final java.util.Set<ActivityType> CHANGE_ENTRIES =
            java.util.EnumSet.of(ActivityType.change_requested, ActivityType.change_approved);

    static boolean touchesImportantField(Map<String, Object> changed) {
        return changed != null && IMPORTANT_FIELDS.stream().anyMatch(changed::containsKey);
    }

    /** Public for EO03, which judges "nothing changed" by the same meaning. */
    public static boolean sameValue(Object a, Object b) {
        if (isEmpty(a) && isEmpty(b)) {
            return true;
        }
        if (a instanceof String sa && b instanceof String sb) {
            try {
                return OffsetDateTime.parse(sa).toInstant().equals(OffsetDateTime.parse(sb).toInstant());
            } catch (java.time.format.DateTimeParseException notADate) {
                return sa.strip().equals(sb.strip());
            }
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new java.math.BigDecimal(na.toString()).compareTo(new java.math.BigDecimal(nb.toString())) == 0;
        }
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            return ca.stream().map(String::valueOf).sorted().toList()
                    .equals(cb.stream().map(String::valueOf).sorted().toList());
        }
        return java.util.Objects.equals(a, b);
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
    }

    /** ECL-C2: when each of these requests was submitted, by request id. A
     * request is submitted once (a resubmission is its own entry type), and
     * one submitted before the timeline existed (V13) has no entry. */
    @Transactional(readOnly = true)
    public Map<UUID, OffsetDateTime> submittedAt(Collection<UUID> requestIds) {
        return repository.findByRequestIdInAndActivityType(requestIds, ActivityType.submitted).stream()
                .collect(Collectors.toMap(RequestActivity::getRequestId, RequestActivity::getOccurredAt,
                        (first, second) -> first.isBefore(second) ? first : second));
    }
}
