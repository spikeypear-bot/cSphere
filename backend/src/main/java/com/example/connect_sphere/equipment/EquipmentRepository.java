package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /** TS09 AC5: per kind, units of a non-serialised kind reported damaged
     * during the period, as {@code [equipment_id, quantity]} rows. Overlapping
     * reports are added up, so the figure never understates what is out of
     * action. A report with no end runs on. Read through
     * {@link EquipmentAvailabilityService}, never on its own. */
    @Query(nativeQuery = true, value = """
            select equipment_id, coalesce(sum(quantity), 0) from equipment_damage_reports
            where equipment_id in (:equipmentIds) and serial_number is null
              and period_start < :end and (period_end is null or period_end > :start)
            group by equipment_id
            """)
    List<Object[]> damagedQuantities(@Param("equipmentIds") Collection<UUID> equipmentIds,
            @Param("start") Instant start, @Param("end") Instant end);
}
