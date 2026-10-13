package com.example.connect_sphere.safety;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSafetyReviewRepository extends JpaRepository<EventSafetyReview, UUID> {
    Optional<EventSafetyReview> findByEventId(UUID eventId);
}
