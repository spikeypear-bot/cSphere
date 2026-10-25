package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentLogRepository extends JpaRepository<EquipmentLog, UUID> {

    List<EquipmentLog> findByEventId(UUID eventId);

    // Reservations of this equipment type that overlap the period — used to compute
    // how much quantity is already committed elsewhere. EO07 (decision-log D28):
    // a cancelled or completed event no longer holds its equipment. Its log rows
    // are kept as history, they just stop counting, as venue availability
    // already ignores bookings of cancelled events.
    @Query(nativeQuery = true, value = """
            select l.* from equipment_logs l
            join events e on e.event_id = l.event_id
            where l.equipment_id = :equipmentId
              and l.loaned_from < :end and l.loaned_until > :start
              and e.status not in ('cancelled', 'completed')
            """)
    List<EquipmentLog> findOverlappingForEquipment(
            @Param("equipmentId") UUID equipmentId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    // The same overlap rule for several kinds at once (one query for the
    // catalogue's availability, D32).
    @Query(nativeQuery = true, value = """
            select l.* from equipment_logs l
            join events e on e.event_id = l.event_id
            where l.equipment_id in (:equipmentIds)
              and l.loaned_from < :end and l.loaned_until > :start
              and e.status not in ('cancelled', 'completed')
            """)
    List<EquipmentLog> findOverlappingForEquipments(
            @Param("equipmentIds") Collection<UUID> equipmentIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    // Is this specific serial number already reserved during the period? (EO07:
    // not by a cancelled or completed event, as above.)
    @Query(nativeQuery = true, value = """
            select l.* from equipment_logs l
            join events e on e.event_id = l.event_id
            where l.equipment_id = :equipmentId
              and l.serial_number = :serialNumber
              and l.loaned_from < :end and l.loaned_until > :start
              and e.status not in ('cancelled', 'completed')
            """)
    List<EquipmentLog> findOverlappingForUnit(
            @Param("equipmentId") UUID equipmentId,
            @Param("serialNumber") String serialNumber,
            @Param("start") Instant start,
            @Param("end") Instant end);
}