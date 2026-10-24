package com.example.connect_sphere.equipmentdamage.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageReport;

import jakarta.persistence.LockModeType;

public interface EquipmentDamageReportRepository extends JpaRepository<EquipmentDamageReport, UUID> {

    List<EquipmentDamageReport> findAllByOrderByReportedAtDesc();

    /** Which kind a report is about, without loading it (it is loaded locked afterwards). */
    @Query("select r.equipmentId from EquipmentDamageReport r where r.reportId = :id")
    Optional<UUID> equipmentIdOf(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EquipmentDamageReport r where r.reportId = :id")
    Optional<EquipmentDamageReport> findForUpdate(@Param("id") UUID id);

    /** Non-serialised damage of this kind overlapping the period. A report with no end runs on. */
    @Query("""
            select r from EquipmentDamageReport r
            where r.equipmentId = :equipmentId and r.serialNumber is null
              and r.periodStart < :end and (r.periodEnd is null or r.periodEnd > :start)
            """)
    List<EquipmentDamageReport> findQuantityDamageOverlapping(@Param("equipmentId") UUID equipmentId,
            @Param("start") Instant start, @Param("end") Instant end);

    /** Damage reported against this unit overlapping the period. */
    @Query("""
            select r from EquipmentDamageReport r
            where r.equipmentId = :equipmentId and r.serialNumber = :serialNumber
              and r.periodStart < :end and (r.periodEnd is null or r.periodEnd > :start)
            """)
    List<EquipmentDamageReport> findUnitDamageOverlapping(@Param("equipmentId") UUID equipmentId,
            @Param("serialNumber") String serialNumber, @Param("start") Instant start, @Param("end") Instant end);
}
