package com.example.connect_sphere.notification.entity;

import java.time.OffsetDateTime;
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

    public Notification() {
    }
}
