package com.example.connect_sphere.equipment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

// @Embeddable = "this class is a bundle of columns that another entity can embed".
@Embeddable
public class SerialisedEquipmentId implements Serializable {

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @Column(name = "serial_number")
    private String serialNumber;

    protected SerialisedEquipmentId() {}

    public SerialisedEquipmentId(UUID equipmentId, String serialNumber) {
        this.equipmentId = equipmentId;
        this.serialNumber = serialNumber;
    }

    public UUID getEquipmentId() { return equipmentId; }
    public String getSerialNumber() { return serialNumber; }

    // Required for composite keys: two ids are equal if BOTH parts match.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SerialisedEquipmentId other)) return false;
        return Objects.equals(equipmentId, other.equipmentId)
                && Objects.equals(serialNumber, other.serialNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(equipmentId, serialNumber);
    }
}