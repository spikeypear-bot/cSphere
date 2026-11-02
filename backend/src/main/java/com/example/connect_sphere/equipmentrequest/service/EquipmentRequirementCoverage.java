package com.example.connect_sphere.equipmentrequest.service;

import java.util.UUID;
import java.util.function.ToLongFunction;
import com.example.connect_sphere.event.entity.Event;

/** Structured approved requirements are minima; legacy free text remains a human judgement. */
public final class EquipmentRequirementCoverage {
    private EquipmentRequirementCoverage() { }
    public static boolean covered(Event event, ToLongFunction<UUID> heldThroughout) {
        return event.getEquipmentItems() == null || event.getEquipmentItems().stream()
                .allMatch(item -> heldThroughout.applyAsLong(item.equipmentId()) >= item.quantity());
    }
}
