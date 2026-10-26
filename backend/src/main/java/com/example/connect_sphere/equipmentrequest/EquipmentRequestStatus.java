package com.example.connect_sphere.equipmentrequest;

public enum EquipmentRequestStatus {
    processing,
    approved,
    rejected,
    /** EC-NEW3 (D36, V52): replaced by a coordinator's complete revision. */
    superseded
}