package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;

public record SubmitEquipmentRequest(String technicalRequirement, List<Item> items) {
    public record Item(UUID equipmentId, int quantity) {}
}
