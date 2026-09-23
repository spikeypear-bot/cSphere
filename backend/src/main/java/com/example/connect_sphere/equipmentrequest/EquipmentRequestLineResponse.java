package com.example.connect_sphere.equipmentrequest;

import java.util.UUID;

public record EquipmentRequestLineResponse(UUID equipmentId, String equipmentName, int quantity) {

    static EquipmentRequestLineResponse from(EquipmentRequestLine line) {
        return new EquipmentRequestLineResponse(
                line.getId().getEquipmentId(), line.getEquipment().getName(), line.getQuantity());
    }
}