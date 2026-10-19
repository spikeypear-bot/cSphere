package com.example.connect_sphere.event.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "event_reassignment_requests")
@Getter
@Setter
public class EventReassignmentRequest {

    @Id
    @Column(name = "reassignment_request_id")
    private UUID reassignmentRequestId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventReassignmentStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    public EventReassignmentRequest() {
    }
}
