package com.example.connect_sphere.equipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "equipment_status_periods")
public class EquipmentStatusPeriod {

    @Id
    @Column(name = "status_period_id")
    private UUID id;

    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    @Column(name = "serial_number", nullable = false)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private EquipmentStatus status;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    // null = no end date (until someone removes the block)
    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EquipmentStatusPeriod() {}

    public EquipmentStatusPeriod(UUID equipmentId, String serialNumber,
                                 EquipmentStatus status, Instant periodStart, Instant periodEnd) {
        this.id = UUID.randomUUID();
        this.equipmentId = equipmentId;
        this.serialNumber = serialNumber;
        this.status = status;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEquipmentId() { return equipmentId; }
    public String getSerialNumber() { return serialNumber; }
    public EquipmentStatus getStatus() { return status; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
}