package com.example.connect_sphere.equipment;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "serialised_equipments")
public class SerialisedEquipment {

    @EmbeddedId
    private SerialisedEquipmentId id;

    // Link to the catalogue row so we can show the name ("Mixer").
    // insertable/updatable = false: the id above already owns the equipment_id column.
    @ManyToOne
    @JoinColumn(name = "equipment_id", insertable = false, updatable = false)
    private Equipment equipment;

    // The database column is a Postgres enum, so tell Hibernate to treat it as one.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private EquipmentStatus status;

    protected SerialisedEquipment() {}

    public SerialisedEquipmentId getId() { return id; }
    public Equipment getEquipment() { return equipment; }
    public EquipmentStatus getStatus() { return status; }
    public void setStatus(EquipmentStatus status) { this.status = status; }
}