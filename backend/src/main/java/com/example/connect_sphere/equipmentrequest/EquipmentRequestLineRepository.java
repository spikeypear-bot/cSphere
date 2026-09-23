package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRequestLineRepository
        extends JpaRepository<EquipmentRequestLine, EquipmentRequestLineId> {
    List<EquipmentRequestLine> findByIdRequestId(java.util.UUID requestId);
}