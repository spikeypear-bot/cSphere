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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EquipmentRequest request where request.eventId = :eventId")
    List<EquipmentRequest> findByEventIdForUpdate(@Param("eventId") UUID eventId);
}