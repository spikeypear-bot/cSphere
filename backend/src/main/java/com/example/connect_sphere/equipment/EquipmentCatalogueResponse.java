package com.example.connect_sphere.equipment;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One kind in the catalogue. The four quantity fields after {@code serialised}
 * are filled only when the caller asks about a window ({@code from}/{@code to},
 * D32) and are left out of the JSON otherwise, so the plain catalogue keeps
 * its original shape.
 */
public record EquipmentCatalogueResponse(
        UUID equipmentId,
        String equipmentName,
        int totalQuantity,
        boolean serialised,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer reservedQuantity,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer outOfActionQuantity,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer availableQuantity) {

    static EquipmentCatalogueResponse of(Equipment equipment) {
        return new EquipmentCatalogueResponse(equipment.getId(), equipment.getName(), equipment.getQuantity(),
                Boolean.TRUE.equals(equipment.getSerialised()), null, null, null);
    }

    static EquipmentCatalogueResponse of(Equipment equipment, EquipmentAvailabilityService.Availability availability) {
        return new EquipmentCatalogueResponse(equipment.getId(), equipment.getName(),
                availability.totalQuantity(), Boolean.TRUE.equals(equipment.getSerialised()),
                availability.reservedQuantity(), availability.outOfActionQuantity(),
                availability.availableQuantity());
    }
}
