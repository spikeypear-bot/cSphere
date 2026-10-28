package com.example.connect_sphere.equipmentrequest;

public enum EquipmentRequestStatus {
    processing,
    approved,
    rejected,
    /** EC-NEW3 (D36, V53): replaced by a coordinator's complete revision. */
    superseded,
    /** EC-NEW3 (D37, V54): approved, but an approved change means it may no
     * longer fit; reservations are still held until the coordinator resolves it. */
    attention;

    /** Approved by Technical Support with its reservations still held. */
    public boolean heldByTechnicalSupport() {
        return this == approved || this == attention;
    }
}