package com.example.connect_sphere.equipmentrequest.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

// The key of one item line of an equipment request: the request and the kind of equipment.
@Tag("unit")
@Tag("EC07")
@Tag("TS06")
class EquipmentRequestLineIdTest {

    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID EQUIPMENT = UUID.randomUUID();

    @Test
    @DisplayName("EC07 TS06 (happy): two keys for the same request and kind are the same line")
    void sameRequestAndKindAreEqual() {
        EquipmentRequestLineId line = new EquipmentRequestLineId(REQUEST, EQUIPMENT);
        assertThat(line).isEqualTo(line);
        assertThat(line).isEqualTo(new EquipmentRequestLineId(REQUEST, EQUIPMENT))
                .hasSameHashCodeAs(new EquipmentRequestLineId(REQUEST, EQUIPMENT));
    }

    @Test
    @DisplayName("EC07 TS06 (negative): a different request, a different kind or another type is a different line")
    void anyDifferenceIsAnotherLine() {
        EquipmentRequestLineId line = new EquipmentRequestLineId(REQUEST, EQUIPMENT);
        assertThat(line).isNotEqualTo(new EquipmentRequestLineId(UUID.randomUUID(), EQUIPMENT));
        assertThat(line).isNotEqualTo(new EquipmentRequestLineId(REQUEST, UUID.randomUUID()));
        assertThat(line).isNotEqualTo(REQUEST);
    }
}
