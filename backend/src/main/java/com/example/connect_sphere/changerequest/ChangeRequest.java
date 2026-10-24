package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** EO03/EC-NEW3 (V32): an organiser's request to change a request or its
 * event, and the coordinator's decision. Values are the changeable fields
 * as JSON (the same keys as SaveEventRequestRequest). */
@Entity
@Table(name = "event_change_requests")
@Getter
@Setter
public class ChangeRequest {

    @Id
    @Column(name = "change_request_id")
    private UUID changeRequestId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_values", nullable = false)
    private Map<String, Object> originalValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_values", nullable = false)
    private Map<String, Object> proposedValues;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acknowledged_impacts")
    private List<Map<String, String>> acknowledgedImpacts;
}
