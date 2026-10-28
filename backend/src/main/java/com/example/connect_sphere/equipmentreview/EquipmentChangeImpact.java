package com.example.connect_sphere.equipmentreview;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.connect_sphere.equipment.EquipmentReservationResponse;

/**
 * TS11 AC4/AC6: does an approved change affect an event's equipment? It does
 * when the new date or time is no longer covered by one of the event's
 * reservations, or when the equipment requirements changed. A change to
 * anything else (name, purpose, attendance, registration…) does not.
 *
 * The same coverage rule as EC-NEW3's impact
 * ({@link EquipmentReservationResponse#covers}), so the coordinator's review
 * and Technical Support's notice cannot disagree.
 */
final class EquipmentChangeImpact {

    static final String START = "startDatetime";
    static final String END = "endDatetime";
    static final String REQUIREMENTS = "equipmentRequirements";
    /** The fields a Technical Support notice compares (AC8). */
    static final List<String> FIELDS = List.of(START, END, REQUIREMENTS);

    record Result(List<EquipmentReservationResponse> uncovered, boolean requirementsChanged) {
    }

    private EquipmentChangeImpact() {
    }

    static Optional<Result> assess(Map<String, Object> updated, Set<String> changedFields,
            List<EquipmentReservationResponse> reservations) {
        boolean timesChanged = changedFields.contains(START) || changedFields.contains(END);
        List<EquipmentReservationResponse> uncovered = List.of();
        Instant start = instant(updated.get(START));
        Instant end = instant(updated.get(END));
        if (timesChanged && start != null && end != null) {
            uncovered = reservations.stream().filter(r -> !r.covers(start, end)).toList();
        }
        boolean requirementsChanged = changedFields.contains(REQUIREMENTS);
        if (uncovered.isEmpty() && !requirementsChanged) {
            return Optional.empty();
        }
        return Optional.of(new Result(uncovered, requirementsChanged));
    }

    /** Just the fields the notice compares, so nothing else is kept with it. */
    static Map<String, Object> compared(Map<String, Object> values) {
        java.util.LinkedHashMap<String, Object> kept = new java.util.LinkedHashMap<>();
        for (String field : FIELDS) {
            Object value = values.get(field);
            if (value != null) {
                kept.put(field, value);
            }
        }
        return kept;
    }

    private static Instant instant(Object value) {
        return value == null ? null : OffsetDateTime.parse(value.toString()).toInstant();
    }
}
