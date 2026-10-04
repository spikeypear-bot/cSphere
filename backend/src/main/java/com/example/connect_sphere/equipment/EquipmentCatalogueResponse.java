package com.example.connect_sphere.equipment;

import java.util.UUID;

public record EquipmentCatalogueResponse(
        UUID equipmentId,
        String equipmentName,
        int totalQuantity,
        boolean serialised) {}
