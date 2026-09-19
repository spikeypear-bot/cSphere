package com.example.connect_sphere.equipment;

// What the frontend sends when changing a status: {"status": "Faulty"}
public record UpdateStatusRequest(EquipmentStatus status) {}