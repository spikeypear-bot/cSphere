package com.example.connect_sphere.equipmentdamage.entity;

import com.example.connect_sphere.equipment.EquipmentStatus;

/**
 * TS09 (V37): the TS03 block a damage report asks to put on the damaged unit
 * when it closes. Only TS03's two blocking statuses; "Available" is not a
 * block.
 */
public enum DamageBlockStatus {
    FAULTY(EquipmentStatus.FAULTY),
    UNAVAILABLE(EquipmentStatus.UNAVAILABLE);

    private final EquipmentStatus equipmentStatus;

    DamageBlockStatus(EquipmentStatus equipmentStatus) {
        this.equipmentStatus = equipmentStatus;
    }

    /** The status TS03's {@code EquipmentStatusService.addPeriod} applies. */
    public EquipmentStatus equipmentStatus() {
        return equipmentStatus;
    }
}
