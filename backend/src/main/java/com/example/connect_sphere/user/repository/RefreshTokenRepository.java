package com.example.connect_sphere.user.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.user.entity.RefreshToken;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * The only lookup on this table. Callers hash the presented token first — the
     * raw value never reaches a query, so it never reaches the query log either.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Kills every live token descended from one login, in a single statement.
     * Used on logout and on reuse detection, where the number of rows is unknown
     * and loading them just to set one field each would be wasteful.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") OffsetDateTime now);
}
