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

    /** TS09 AC5: units of a non-serialised kind reported damaged during the
     * period. Overlapping reports are added up, so the figure never
     * understates what is out of action. A report with no end runs on. */
    @Query(nativeQuery = true, value = """
            select coalesce(sum(quantity), 0) from equipment_damage_reports
            where equipment_id = :equipmentId and serial_number is null
              and period_start < :end and (period_end is null or period_end > :start)
            """)
    int damagedQuantity(@Param("equipmentId") UUID equipmentId,
            @Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
}
