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

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "attention_reason")
    private String attentionReason;

    protected EquipmentRequest() {}

    public EquipmentRequest(UUID id, UUID eventId, EquipmentRequestStatus status, String technicalRequirement) {
        this.id = id;
        this.eventId = eventId;
        this.status = status;
        this.technicalRequirement = technicalRequirement;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public EquipmentRequestStatus getStatus() { return status; }
    public String getTechnicalRequirement() { return technicalRequirement; }
    public String getRejectReason() { return rejectReason; }
    public String getAttentionReason() { return attentionReason; }

    public void decide(EquipmentRequestStatus status, String rejectReason) {
        this.status = status;
        this.rejectReason = rejectReason;
    }

    /** EC-NEW3 (D36): a revision replaces this request with a new one; kept,
     * not deleted, as the before the audit needs. */
    public void supersede() {
        this.status = EquipmentRequestStatus.superseded;
    }

    /** EC-NEW3 (D37): an approved change means this approved request may no longer fit. */
    public void flagAttention(String reason) {
        this.status = EquipmentRequestStatus.attention;
        this.attentionReason = reason;
    }

    /** EC-NEW3 (D37): the coordinator judged nothing about the items needs to change. */
    public void markStillSuitable() {
        this.status = EquipmentRequestStatus.approved;
        this.attentionReason = null;
    }
}