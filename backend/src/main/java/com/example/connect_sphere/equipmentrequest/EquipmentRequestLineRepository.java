package com.example.connect_sphere.equipmentrequest;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRequestLineRepository
        extends JpaRepository<EquipmentRequestLine, EquipmentRequestLineId> {
    List<EquipmentRequestLine> findByIdRequestId(java.util.UUID requestId);

    /** D32: the lines of several requests, with their equipment, in one query. */
    @Query("select l from EquipmentRequestLine l join fetch l.equipment where l.id.requestId in :requestIds")
    List<EquipmentRequestLine> findByRequestIds(@Param("requestIds") Collection<java.util.UUID> requestIds);
}