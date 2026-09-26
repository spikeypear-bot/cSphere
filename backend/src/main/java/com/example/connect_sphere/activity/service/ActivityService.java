package com.example.connect_sphere.activity.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/**
 * Writes and reads the request timeline.
 *
 * <p>{@link #record} is {@code MANDATORY}, the opposite of
 * NotificationService's {@code REQUIRES_NEW}, and on purpose. A notification
 * is a side effect that must never undo the action it reports. A timeline
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

    public ActivityService(RequestActivityRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

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
        return repository.findVisible(requestId, viewerRole.name()).stream()
                .map(a -> new ActivityDto(
                        a.getActivityId(), a.getActivityType().name(), a.getActorName(), a.getActorRole(),
                        a.getMessage(), List.copyOf(a.getFlaggedFields()), a.getFromStatus(), a.getToStatus(),
                        a.getOccurredAt(), a.getFieldQuestions(), a.getFieldValues()))
                .toList();
    }
}
