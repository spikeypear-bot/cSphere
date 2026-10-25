package com.example.connect_sphere.equipmentdamage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.equipment.EquipmentStatus;
import com.example.connect_sphere.equipmentdamage.dto.ReportDamageRequest;
import com.example.connect_sphere.equipmentdamage.entity.DamageBlockStatus;

/**
 * TS09 AC4 as generalised by V37 (decision-log D32): a damage report asks for
 * a TS03 block, Faulty or Unavailable, through {@code blockStatus}; the older
 * {@code markFaulty: true} still means Faulty. Pure value logic, so no
 * doubles.
 */
@Tag("unit")
@Tag("TS09")
@Tag("TS03")
class ReportDamageRequestTest {

    private static ReportDamageRequest request(Boolean markFaulty, String blockStatus) {
        return new ReportDamageRequest(UUID.randomUUID(), "PJ-1", null, "Lamp failed",
                Instant.parse("2027-03-01T00:00:00Z"), null, markFaulty, blockStatus);
    }

    @Test
    @DisplayName("TS09 (happy): blockStatus UNAVAILABLE or FAULTY, in any case, is the block asked for")
    void blockStatusIsReadInAnyCase() {
        assertThat(request(null, "UNAVAILABLE").requestedBlock()).isEqualTo(DamageBlockStatus.UNAVAILABLE);
        assertThat(request(false, " unavailable ").requestedBlock()).isEqualTo(DamageBlockStatus.UNAVAILABLE);
        assertThat(request(null, "Faulty").requestedBlock()).isEqualTo(DamageBlockStatus.FAULTY);
    }

    @Test
    @DisplayName("TS09 (cross-cutting): markFaulty true with no blockStatus still asks for Faulty, and agrees with blockStatus FAULTY")
    void markFaultyIsStillFaulty() {
        assertThat(request(true, null).requestedBlock()).isEqualTo(DamageBlockStatus.FAULTY);
        assertThat(request(true, "").requestedBlock()).isEqualTo(DamageBlockStatus.FAULTY);
        assertThat(request(true, "FAULTY").requestedBlock()).isEqualTo(DamageBlockStatus.FAULTY);
    }

    @Test
    @DisplayName("TS09 (boundary): neither field, or markFaulty false alone, asks for no block")
    void nothingAskedMeansNoBlock() {
        assertThat(request(null, null).requestedBlock()).isNull();
        assertThat(request(false, null).requestedBlock()).isNull();
        assertThat(request(false, "  ").requestedBlock()).isNull();
    }

    @Test
    @DisplayName("TS09 (negative): AVAILABLE, an unknown word, or markFaulty with UNAVAILABLE is refused with 400")
    void invalidBlocksAreRefused() {
        for (ReportDamageRequest bad : new ReportDamageRequest[] {request(null, "AVAILABLE"), request(null, "broken"),
                request(true, "UNAVAILABLE")}) {
            assertThatThrownBy(bad::requestedBlock)
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    @DisplayName("TS09 (happy): each block applies the matching TS03 status")
    void blocksMapToTs03Statuses() {
        assertThat(DamageBlockStatus.FAULTY.equipmentStatus()).isEqualTo(EquipmentStatus.FAULTY);
        assertThat(DamageBlockStatus.UNAVAILABLE.equipmentStatus()).isEqualTo(EquipmentStatus.UNAVAILABLE);
    }
}
