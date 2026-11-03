package com.example.connect_sphere.equipment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

// The key of one serial-numbered unit: its kind and its serial number.
@Tag("unit")
@Tag("TS02")
class SerialisedEquipmentIdTest {

    private static final UUID KIND = UUID.randomUUID();

    @Test
    @DisplayName("TS02 (happy): the same kind and serial number are the same unit")
    void sameKindAndSerialAreEqual() {
        SerialisedEquipmentId unit = new SerialisedEquipmentId(KIND, "PRJ-001");
        assertThat(unit).isEqualTo(unit);
        assertThat(unit).isEqualTo(new SerialisedEquipmentId(KIND, "PRJ-001"))
                .hasSameHashCodeAs(new SerialisedEquipmentId(KIND, "PRJ-001"));
    }

    @Test
    @DisplayName("TS02 (negative): another kind, another serial number or another type is a different unit")
    void anyDifferenceIsAnotherUnit() {
        SerialisedEquipmentId unit = new SerialisedEquipmentId(KIND, "PRJ-001");
        assertThat(unit).isNotEqualTo(new SerialisedEquipmentId(UUID.randomUUID(), "PRJ-001"));
        assertThat(unit).isNotEqualTo(new SerialisedEquipmentId(KIND, "PRJ-002"));
        assertThat(unit).isNotEqualTo("PRJ-001");
    }
}
