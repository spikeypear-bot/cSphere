package com.example.connect_sphere.equipment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SerialisedEquipmentRepository
        extends JpaRepository<SerialisedEquipment, SerialisedEquipmentId> {
}