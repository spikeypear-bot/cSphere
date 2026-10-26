package com.example.connect_sphere.changerequest;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRequirementReviewRepository extends JpaRepository<VenueRequirementReview, UUID> {
    java.util.List<VenueRequirementReview> findByBookingIdOrderByApprovedAtDescReviewIdAsc(UUID bookingId);
    java.util.List<VenueRequirementReview> findByEventIdOrderByApprovedAtDescReviewIdAsc(UUID eventId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from VenueRequirementReview r where r.reviewId = :id")
    java.util.Optional<VenueRequirementReview> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    boolean existsByChangeRequestIdAndBookingId(UUID changeRequestId, UUID bookingId);
}
