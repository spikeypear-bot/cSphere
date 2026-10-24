package com.example.connect_sphere.equipmentreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.equipment.EquipmentReservationResponse;

@Tag("unit")
@Tag("TS11")
class EquipmentChangeImpactTest {

    private static final Map<String, Object> NEW_TIME = Map.of(
            "startDatetime", "2027-03-10T10:00+08:00", "endDatetime", "2027-03-10T13:00+08:00");
    private static final Set<String> TIMES = Set.of("startDatetime", "endDatetime");

    private static EquipmentReservationResponse reserved(String from, String until) {
        return new EquipmentReservationResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Mic", 2,
                null, Instant.parse(from), Instant.parse(until));
    }

    @Test
    @DisplayName("TS11 AC4 (boundary): a reservation exactly covering the new time is not affected")
    void exactCoverIsNotAffected() {
        // 10:00-13:00 Singapore is 02:00-05:00Z.
        var exact = reserved("2027-03-10T02:00:00Z", "2027-03-10T05:00:00Z");

        assertThat(EquipmentChangeImpact.assess(NEW_TIME, TIMES, List.of(exact))).isEmpty();
    }

    @Test
    @DisplayName("TS11 AC4 (boundary): a reservation ending one minute before the new end is affected")
    void oneMinuteShortIsAffected() {
        var shortBy = reserved("2027-03-10T02:00:00Z", "2027-03-10T04:59:00Z");

        var result = EquipmentChangeImpact.assess(NEW_TIME, TIMES, List.of(shortBy));

        assertThat(result).isPresent();
        assertThat(result.get().uncovered()).containsExactly(shortBy);
        assertThat(result.get().requirementsChanged()).isFalse();
    }

    @Test
    @DisplayName("TS11 AC4 (boundary): a reservation starting one minute after the new start is affected")
    void oneMinuteLateIsAffected() {
        var late = reserved("2027-03-10T02:01:00Z", "2027-03-10T05:00:00Z");

        assertThat(EquipmentChangeImpact.assess(NEW_TIME, TIMES, List.of(late)).orElseThrow().uncovered())
                .containsExactly(late);
    }

    @Test
    @DisplayName("TS11 AC4 (happy): changed equipment requirements affect equipment even with every reservation still covering")
    void changedRequirementsAffect() {
        var covering = reserved("2027-03-10T01:00:00Z", "2027-03-10T06:00:00Z");

        var result = EquipmentChangeImpact.assess(NEW_TIME, Set.of("equipmentRequirements"), List.of(covering));

        assertThat(result.orElseThrow().requirementsChanged()).isTrue();
        assertThat(result.get().uncovered()).isEmpty();
    }

    @Test
    @DisplayName("TS11 AC6 (negative): a change to attendance only does not affect equipment, whatever the reservations")
    void otherFieldsDoNotAffect() {
        // Even a reservation that would not cover the stored times is not re-judged when times did not change.
        var old = reserved("2027-03-10T01:00:00Z", "2027-03-10T04:00:00Z");

        assertThat(EquipmentChangeImpact.assess(NEW_TIME, Set.of("expectedAttendance"), List.of(old))).isEmpty();
    }

    @Test
    @DisplayName("TS11 AC8 (happy): only the start, end and equipment requirements are kept for the comparison")
    void onlyComparedFieldsAreKept() {
        Map<String, Object> all = Map.of("startDatetime", "a", "endDatetime", "b", "equipmentRequirements", "c",
                "expectedAttendance", 150, "eventName", "Gala");

        assertThat(EquipmentChangeImpact.compared(all)).containsOnlyKeys("startDatetime", "endDatetime",
                "equipmentRequirements");
    }
}
