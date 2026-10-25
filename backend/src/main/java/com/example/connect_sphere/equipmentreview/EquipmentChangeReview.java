package com.example.connect_sphere.equipmentreview;

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

/**
 * TS11 (V41): an approved event change that affected the event's equipment,
 * and Technical Support's review of it. The values and affected reservations
 * are snapshots taken at approval, so a later change cannot overwrite them
 * (AC9). No outcome yet means the event is flagged as needing review (AC14).
 */
@Entity
@Table(name = "equipment_change_reviews")
@Getter
@Setter
public class EquipmentChangeReview {

    @Id
    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "change_request_id", nullable = false)
    private UUID changeRequestId;

    @Column(name = "coordinator_id")
    private UUID coordinatorId;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    /** startDatetime, endDatetime and equipmentRequirements before the change. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_values", nullable = false)
    private Map<String, Object> previousValues;

    /** The same keys after the change. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "updated_values", nullable = false)
    private Map<String, Object> updatedValues;

    /** Each: logId, equipmentName, quantity, serialNumber, loanedFrom, loanedUntil. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_reservations", nullable = false)
    private List<Map<String, Object>> affectedReservations;

    @Column(name = "requirements_changed", nullable = false)
    private boolean requirementsChanged;

    @Enumerated(EnumType.STRING)
    private ReviewOutcome outcome;

    @Column(name = "outcome_note")
    private String outcomeNote;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    public boolean needsReview() {
        return outcome == null;
    }
}
