package com.example.connect_sphere.changerequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {

    /** Deciding or withdrawing takes the row lock, so two actions cannot both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChangeRequest c WHERE c.changeRequestId = :id")
    Optional<ChangeRequest> findForUpdate(@Param("id") UUID id);

    List<ChangeRequest> findByRequestIdOrderByRequestedAtDesc(UUID requestId);

    Optional<ChangeRequest> findFirstByRequestIdAndStatus(UUID requestId, ChangeRequestStatus status);

    List<ChangeRequest> findByStatusOrderByRequestedAtAsc(ChangeRequestStatus status);
}
