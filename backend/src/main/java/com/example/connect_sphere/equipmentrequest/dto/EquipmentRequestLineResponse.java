package com.example.connect_sphere.equipmentrequest.dto;

import com.example.connect_sphere.equipmentrequest.entity.EquipmentRequestLine;
import java.util.UUID;

public record EquipmentRequestLineResponse(UUID equipmentId, String equipmentName, int quantity) {

    public static EquipmentRequestLineResponse from(EquipmentRequestLine line) {
        return new EquipmentRequestLineResponse(
                line.getId().getEquipmentId(), line.getEquipment().getName(), line.getQuantity());
    }
}