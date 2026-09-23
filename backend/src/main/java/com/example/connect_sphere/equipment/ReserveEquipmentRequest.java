package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.UUID;

// serialNumber is null for a quantity-only reservation of non-serialised equipment.
public record ReserveEquipmentRequest(
        UUID eventId,
        UUID equipmentId,
        int quantity,
        String serialNumber,
        Instant start,
        Instant end) {}