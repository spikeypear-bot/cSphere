package com.example.connect_sphere.safety;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadinessItemStateRepository extends JpaRepository<ReadinessItemState, UUID> {
    List<ReadinessItemState> findByEventId(UUID eventId);
    Optional<ReadinessItemState> findByEventIdAndItemKey(UUID eventId, String itemKey);
}
