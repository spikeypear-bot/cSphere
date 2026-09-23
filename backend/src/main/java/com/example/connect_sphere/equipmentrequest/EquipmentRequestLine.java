package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.equipment.Equipment;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "equipment_request_equipments")
public class EquipmentRequestLine {

    @EmbeddedId
    private EquipmentRequestLineId id;

    @ManyToOne
    @JoinColumn(name = "equipment_id", insertable = false, updatable = false)
    private Equipment equipment;

    @Column(name = "equipment_qty", nullable = false)
    private int quantity;

    protected EquipmentRequestLine() {}

    public EquipmentRequestLineId getId() { return id; }
    public Equipment getEquipment() { return equipment; }
    public int getQuantity() { return quantity; }
}