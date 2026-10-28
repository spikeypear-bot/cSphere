package com.example.connect_sphere.equipmentreview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EquipmentChangeReviewRepository extends JpaRepository<EquipmentChangeReview, UUID> {

    /** AC15: one review per approved change. */
    boolean existsByChangeRequestId(UUID changeRequestId);

    List<EquipmentChangeReview> findAllByOrderByChangedAtDesc();

    List<EquipmentChangeReview> findByEventIdOrderByChangedAtDesc(UUID eventId);

    /** Two Technical Support users recording an outcome at once: the second
     * waits for the first, then finds it already reviewed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EquipmentChangeReview r WHERE r.reviewId = :id")
    Optional<EquipmentChangeReview> findForUpdate(@Param("id") UUID id);
}
