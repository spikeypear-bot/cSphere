package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;

/**
 * EC07. {@code shortfallOutcomeId} (TS09 AC19) marks an additional request for
 * an equipment shortfall left by a damage report; without it, EC07's rule of
 * one live request per event applies as before.
 */
public record SubmitEquipmentRequest(String technicalRequirement, List<Item> items, UUID shortfallOutcomeId) {
    public record Item(UUID equipmentId, int quantity) {}

    public SubmitEquipmentRequest(String technicalRequirement, List<Item> items) {
        this(technicalRequirement, items, null);
    }
}
