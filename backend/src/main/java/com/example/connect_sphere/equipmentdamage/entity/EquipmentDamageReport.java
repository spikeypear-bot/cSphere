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
 * TS09 (V35): Technical Support's report that equipment is damaged for a
 * period. A serialised report names one unit (quantity 1); a non-serialised
 * report gives how many units of the kind are out of action, which then stop
 * counting as available (AC5). Each event it affects gets an
 * {@link EquipmentDamageOutcome}.
 */
@Entity
@Table(name = "equipment_damage_reports")
@Getter
@Setter
public class EquipmentDamageReport {

    @Id
    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    /** Set for a serialised unit; null for a quantity of a non-serialised kind. */
    @Column(name = "serial_number", length = 64)
    private String serialNumber;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String description;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    /** Null means the damage has no known end. */
    @Column(name = "period_end")
    private Instant periodEnd;

    /** AC4 (V37): the TS03 block Technical Support asked for on the damaged
     * unit, Faulty or Unavailable; null when none was asked for. */
    @Enumerated(EnumType.STRING)
    @Column(name = "block_status")
    private DamageBlockStatus blockStatus;

    /** Whether that block was actually added when the report closed. */
    @Column(name = "block_applied", nullable = false)
    private boolean blockApplied;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DamageReportStatus status;

    @Column(name = "reported_by", nullable = false)
    private UUID reportedBy;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public EquipmentDamageReport() {
    }

    public boolean isSerialised() {
        return serialNumber != null;
    }
}
