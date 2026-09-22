package com.example.connect_sphere.equipment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EquipmentStatus {
    AVAILABLE("Available"),
    FAULTY("Faulty"),
    UNAVAILABLE("Unavailable");

    private final String label;

    EquipmentStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static EquipmentStatus fromLabel(String value) {
        for (EquipmentStatus status : values()) {
            if (status.label.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + value);
    }
}