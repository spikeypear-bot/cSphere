package com.example.connect_sphere.equipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "equipments")
public class Equipment {

    @Id
    @Column(name = "equipment_id")
    private UUID id;

    @Column(name = "equipment_name", nullable = false)
    private String name;

    @Column(name = "equipment_qty", nullable = false)
    private int quantity;

    private Boolean serialised;

    @Column(name = "equipment_type", nullable = false)
    private char type;

    protected Equipment() {}

    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public Boolean getSerialised() { return serialised; }
    public char getType() { return type; }
}