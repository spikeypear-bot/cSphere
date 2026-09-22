package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentLogRepository extends JpaRepository<EquipmentLog, UUID> {

    List<EquipmentLog> findByEventId(UUID eventId);

    // Reservations of this equipment type that overlap the period — used to compute
    // how much quantity is already committed elsewhere.
    @Query("""
            select l from EquipmentLog l
            where l.equipmentId = :equipmentId
              and l.loanedFrom < :end and l.loanedUntil > :start
            """)
    List<EquipmentLog> findOverlappingForEquipment(
            @Param("equipmentId") UUID equipmentId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    // Is this specific serial number already reserved during the period?
    @Query("""
            select l from EquipmentLog l
            where l.equipmentId = :equipmentId
              and l.serialNumber = :serialNumber
              and l.loanedFrom < :end and l.loanedUntil > :start
            """)
    List<EquipmentLog> findOverlappingForUnit(
            @Param("equipmentId") UUID equipmentId,
            @Param("serialNumber") String serialNumber,
            @Param("start") Instant start,
            @Param("end") Instant end);
}