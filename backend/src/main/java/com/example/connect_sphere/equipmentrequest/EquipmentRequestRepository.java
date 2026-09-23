package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRequestRepository extends JpaRepository<EquipmentRequest, UUID> {
    List<EquipmentRequest> findByStatus(EquipmentRequestStatus status);
    List<EquipmentRequest> findByEventId(UUID eventId);
}