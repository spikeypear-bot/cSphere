package com.example.connect_sphere.notification.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to `notifications` (V12 migration) — EO09 (status-change) and EO19
 * (coordinator-assignment) both create rows here rather than each inventing
 * their own delivery mechanism, since the two stories' ACs are otherwise
 * near-identical (identify the event, say what happened, when; unread/read;
 * deep link; never exposed cross-organisation; retained until the team
 * agrees a retention policy — none has been, so nothing here ever deletes a
 * row).
 *
 * Deliberately denormalised: {@code eventName}/{@code coordinatorName}/
 * {@code coordinatorEmail} are snapshots taken by {@code NotificationService}
 * at the moment of the triggering action, not live joins to the current
 * event/request/user rows. A notification is a historical record of what was
 * true when it was sent — see the migration's own comment for why that
 * matters for a later rename/reassignment/deletion.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

    @Id
    @Column(name = "notification_id")
    private UUID notificationId;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "event_request_id")
    private UUID eventRequestId;

    @Column(name = "event_id")
    private UUID eventId;

    /** AT03: the exact registration, retained even after a later re-registration. */
    @Column(name = "registration_id")
    private UUID registrationId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private NotificationType type;

    @Column(name = "event_name")
    private String eventName;

    /** Text, not a Java enum — see the V12 migration's own comment: this spans
     * two independent status vocabularies (event_request_status,
     * event_status) that a shared enum would couple together for no benefit. */
    @Column(name = "new_status")
    private String newStatus;

    private String reason;

    @Column(name = "coordinator_name")
    private String coordinatorName;

    @Column(name = "coordinator_email")
    private String coordinatorEmail;

    @Column(name = "is_reassignment")
    private Boolean isReassignment;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    /** Null means unread. Never anything else meaningful — there is no
     * "unread again" state, matching EO09/EO19's own AC that marking as read
     * never changes the underlying event/coordinator data. */
    @Column(name = "read_at")
    private OffsetDateTime readAt;

    /** V13: the clarification message / organiser response being sent. */
    private String message;

    /** V13: the booking a venue_booking_requested notification is about. */
    @Column(name = "venue_booking_id")
    private UUID venueBookingId;

    /** VS17: deduplication and historical review reference. */
    @Column(name = "requirement_review_id")
    private UUID requirementReviewId;

    /** EC-NEW4: who did it, for "not about your own actions"; not stored. */
    @jakarta.persistence.Transient
    private UUID actorUserId;

    /** EC-NEW4 (V54): who did what this is about; null when the system did it. */
    @Column(name = "actor_name")
    private String actorName;

    /** EC-NEW4 (V54): their role, as the token's role claim (e.g. "ecl"). */
    @Column(name = "actor_role")
    private String actorRole;

    /** AT09: immutable values from the approved change, not a live event join. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_changes", columnDefinition = "jsonb")
    private List<NotificationChange> notificationChanges;

    /** AT09: the event time as it stood when cancellation was committed. */
    @Column(name = "original_event_start_datetime")
    private OffsetDateTime originalEventStartDatetime;

    @Column(name = "original_event_end_datetime")
    private OffsetDateTime originalEventEndDatetime;

    public Notification() {
    }
}
