package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentStatusPeriodRepository
        extends JpaRepository<EquipmentStatusPeriod, UUID> {

    // Blocks (all units) that overlap the period. A block with no end runs forever.
    @Query("""
            select p from EquipmentStatusPeriod p
            where p.periodStart < :end
              and (p.periodEnd is null or p.periodEnd > :start)
            order by p.createdAt desc
            """)
    List<EquipmentStatusPeriod> findOverlapping(
            @Param("start") Instant start, @Param("end") Instant end);

    // Same overlap rule, for one unit. Used to refuse conflicting blocks.
    @Query("""
            select p from EquipmentStatusPeriod p
            where p.equipmentId = :equipmentId
              and p.serialNumber = :serialNumber
              and p.periodStart < :end
              and (p.periodEnd is null or p.periodEnd > :start)
            """)
    List<EquipmentStatusPeriod> findOverlappingForUnit(
            @Param("equipmentId") UUID equipmentId,
            @Param("serialNumber") String serialNumber,
            @Param("start") Instant start,
            @Param("end") Instant end);

    // Spring writes this query for us from the method name.
    List<EquipmentStatusPeriod> findByEquipmentIdAndSerialNumberOrderByPeriodStartAsc(
            UUID equipmentId, String serialNumber);
}