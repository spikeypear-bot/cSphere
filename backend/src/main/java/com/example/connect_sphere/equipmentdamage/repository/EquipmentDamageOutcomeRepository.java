package com.example.connect_sphere.equipmentdamage.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;

import jakarta.persistence.LockModeType;

public interface EquipmentDamageOutcomeRepository extends JpaRepository<EquipmentDamageOutcome, UUID> {

    List<EquipmentDamageOutcome> findByReportId(UUID reportId);

    List<EquipmentDamageOutcome> findByEventId(UUID eventId);

    Optional<EquipmentDamageOutcome> findByAdditionalRequestId(UUID requestId);

    /** D32: which of these equipment requests answer a shortfall (AC19). */
    @Query("select o.additionalRequestId from EquipmentDamageOutcome o where o.additionalRequestId in :requestIds")
    List<UUID> shortfallRequestIds(@Param("requestIds") java.util.Collection<UUID> requestIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EquipmentDamageOutcome o where o.outcomeId = :id")
    Optional<EquipmentDamageOutcome> findForUpdate(@Param("id") UUID id);

    /** Units of this kind the event has been recorded short of, over every
     * damage report. {@code excluded} leaves one outcome out (the one being
     * decided again); pass a random id to count them all. */
    @Query(nativeQuery = true, value = """
            select coalesce(sum(o.shortfall), 0) from equipment_damage_outcomes o
            join equipment_damage_reports r on r.report_id = o.report_id
            where o.event_id = :eventId and r.equipment_id = :equipmentId and o.outcome_id <> :excluded
            """)
    int recordedShortfall(@Param("eventId") UUID eventId, @Param("equipmentId") UUID equipmentId,
            @Param("excluded") UUID excluded);
}
