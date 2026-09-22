package com.example.connect_sphere.equipmentrequest;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class EquipmentRequestLineId implements Serializable {

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "equipment_id")
    private UUID equipmentId;

    protected EquipmentRequestLineId() {}

    public EquipmentRequestLineId(UUID requestId, UUID equipmentId) {
        this.requestId = requestId;
        this.equipmentId = equipmentId;
    }

    public UUID getRequestId() { return requestId; }
    public UUID getEquipmentId() { return equipmentId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EquipmentRequestLineId other)) return false;
        return Objects.equals(requestId, other.requestId)
                && Objects.equals(equipmentId, other.equipmentId);
    }

    @Override
    public int hashCode() { return Objects.hash(requestId, equipmentId); }
}