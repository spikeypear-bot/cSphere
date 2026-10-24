package com.example.connect_sphere.equipmentdamage.entity;

import java.time.Instant;
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
 * TS09 (V35): one event affected by an {@link EquipmentDamageReport}, and
 * what was decided for it. Its own class rather than fields on the report:
 * each affected event has its own outcome, shortfall and resolution.
 */
@Entity
@Table(name = "equipment_damage_outcomes")
@Getter
@Setter
public class EquipmentDamageOutcome {

    @Id
    @Column(name = "outcome_id")
    private UUID outcomeId;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Null until Technical Support decides. */
    @Enumerated(EnumType.STRING)
    private DamageOutcome outcome;

    /** Units the event is short of what its approved requests asked for. */
    @Column(nullable = false)
    private int shortfall;

    /** Non-serialised: how many units were taken off this event's reservations. */
    @Column(name = "reduced_by", nullable = false)
    private int reducedBy;

    @Column(name = "replacement_serial_number", length = 64)
    private String replacementSerialNumber;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** AC19: the coordinator's additional request for this shortfall. */
    @Column(name = "additional_request_id")
    private UUID additionalRequestId;

    @Enumerated(EnumType.STRING)
    private ShortfallResolution resolution;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public EquipmentDamageOutcome() {
    }

    public EquipmentDamageOutcome(UUID reportId, UUID eventId) {
        this.outcomeId = UUID.randomUUID();
        this.reportId = reportId;
        this.eventId = eventId;
    }

    public boolean isDecided() {
        return outcome != null;
    }

    /** A shortfall the coordinator still has to deal with. */
    public boolean isOpenShortfall() {
        return outcome == DamageOutcome.SHORT && resolvedAt == null;
    }
}
