package com.example.connect_sphere.equipment;

import java.util.UUID;

// Availability for one equipment type, for the period the caller asked about.
public record EquipmentAvailabilityResponse(
        UUID equipmentId,
        String equipmentName,
        boolean serialised,
        int totalQuantity,
        int availableQuantity) {}