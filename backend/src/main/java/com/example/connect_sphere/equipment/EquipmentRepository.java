package com.example.connect_sphere.equipment;

import java.util.UUID;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select equipment from Equipment equipment where equipment.id = :equipmentId")
    Optional<Equipment> findForUpdate(@Param("equipmentId") UUID equipmentId);
}
