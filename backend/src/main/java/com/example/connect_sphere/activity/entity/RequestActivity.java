package com.example.connect_sphere.activity.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One timeline entry (V13 {@code event_request_activity}). {@code @Immutable}
 * mirrors the table's append-only trigger: Hibernate may insert a row but
 * never issues an UPDATE for one, so the trigger is the second line of
 * defence rather than the first.
 */
@Entity
@Immutable
@Table(name = "event_request_activity")
@Getter
@Setter
public class RequestActivity {

    @Id
    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type")
    private ActivityType activityType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(name = "actor_name")
    private String actorName;

    private String message;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flagged_fields", columnDefinition = "text[]")
    private List<String> flaggedFields = new ArrayList<>();

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "audience_roles", columnDefinition = "text[]")
    private List<String> audienceRoles = new ArrayList<>();

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    public RequestActivity() {
    }
}
