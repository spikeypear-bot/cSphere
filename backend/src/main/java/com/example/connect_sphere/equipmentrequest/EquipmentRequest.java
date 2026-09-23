package com.example.connect_sphere.equipmentrequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "equipment_requests")
public class EquipmentRequest {

    @Id
    @Column(name = "request_id")
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private EquipmentRequestStatus status;

    @Column(name = "technical_requirement", nullable = false)
    private String technicalRequirement;

    protected EquipmentRequest() {}

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public EquipmentRequestStatus getStatus() { return status; }
    public String getTechnicalRequirement() { return technicalRequirement; }
}