package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentRequestRepository extends JpaRepository<EquipmentRequest, UUID> {
    List<EquipmentRequest> findByStatus(EquipmentRequestStatus status);
    List<EquipmentRequest> findByEventId(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EquipmentRequest request where request.id = :requestId")
    Optional<EquipmentRequest> findForUpdate(@Param("requestId") UUID requestId);

    /** EO07 AC12: whether the event is cancelled or finished, so no more
     * equipment may be reserved for it. */
    @Query(nativeQuery = true, value = "select exists (select 1 from events where event_id = :eventId "
            + "and status in ('cancelled', 'completed'))")
    boolean eventClosed(@Param("eventId") UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EquipmentRequest request where request.eventId = :eventId")
    List<EquipmentRequest> findByEventIdForUpdate(@Param("eventId") UUID eventId);
}