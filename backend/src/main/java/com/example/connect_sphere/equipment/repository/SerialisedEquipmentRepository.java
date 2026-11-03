package com.example.connect_sphere.equipment.repository;

import com.example.connect_sphere.equipment.entity.SerialisedEquipment;
import com.example.connect_sphere.equipment.entity.SerialisedEquipmentId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SerialisedEquipmentRepository
        extends JpaRepository<SerialisedEquipment, SerialisedEquipmentId> {

    /** D32: one kind's units, by serial number. */
    List<SerialisedEquipment> findByIdEquipmentIdOrderByIdSerialNumberAsc(UUID equipmentId);
}