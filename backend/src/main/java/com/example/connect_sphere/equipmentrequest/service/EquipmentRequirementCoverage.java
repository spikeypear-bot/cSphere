package com.example.connect_sphere.equipmentrequest.service;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.function.ToLongFunction;

import com.example.connect_sphere.equipment.dto.EquipmentReservationResponse;
import com.example.connect_sphere.event.entity.Event;

/** Structured approved requirements are minima; legacy free text remains a human judgement. */
public final class EquipmentRequirementCoverage {
    private EquipmentRequirementCoverage() { }
    public static boolean covered(Event event, ToLongFunction<UUID> heldThroughout) {
        return event.getEquipmentItems() == null || event.getEquipmentItems().stream()
                .allMatch(item -> heldThroughout.applyAsLong(item.equipmentId()) >= item.quantity());
    }

    /** {@link #covered} counting, per kind, the listed reservations that span the event's whole time. */
    public static boolean coveredBy(Event event, Collection<EquipmentReservationResponse> held) {
        Instant start = event.getStartDatetime().toInstant();
        Instant end = event.getEndDatetime().toInstant();
        return covered(event, id -> held.stream()
                .filter(r -> id.equals(r.equipmentId()) && r.covers(start, end))
                .mapToLong(EquipmentReservationResponse::quantity).sum());
    }
}
