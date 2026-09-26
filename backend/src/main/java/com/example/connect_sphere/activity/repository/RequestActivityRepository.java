package com.example.connect_sphere.activity.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.activity.entity.RequestActivity;

@Repository
public interface RequestActivityRepository extends JpaRepository<RequestActivity, UUID> {

    /** The audience filter is part of the query, so an entry a role may not
     * see never leaves the database for that role (AI_Context checklist step
     * 5: which rows a caller gets is a WHERE clause, not a UI decision). The
     * activity_id tiebreak keeps the order stable for entries written in the
     * same instant. */
    @Query(value = """
            SELECT * FROM event_request_activity
            WHERE request_id = :requestId AND :role = ANY(audience_roles)
            ORDER BY occurred_at, activity_id
            """, nativeQuery = true)
    List<RequestActivity> findVisible(@Param("requestId") UUID requestId, @Param("role") String role);
}
