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

/**
 * EO07 (V32): an Event Organiser's request to cancel an approved event, and
 * the assigned Event Coordinator's decision on it. Its own class rather than
 * fields on {@link Event}: an event can be asked about more than once (a
 * declined request may be followed by another, AC9), and each request carries
 * its own reason, times and outcome.
 */
@Entity
@Table(name = "event_cancellation_requests")
@Getter
@Setter
public class EventCancellationRequest {

    @Id
    @Column(name = "cancellation_request_id")
    private UUID cancellationRequestId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCancellationStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    public EventCancellationRequest() {
    }
}
