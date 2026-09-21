package com.example.connect_sphere.user.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One issued refresh token (V11__refresh_tokens.sql, D19 stage 6).
 *
 * A rotated or revoked row is kept rather than deleted: the ability to recognise
 * an already-used token is what makes reuse detection possible, and a deleted row
 * is indistinguishable from one that never existed.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @Column(name = "token_id", nullable = false)
    private UUID tokenId;

    /**
     * Lazy because rotation usually only needs the token's state; the account is
     * fetched on the successful path, where a new access token has to be minted
     * from the user's current role and organisation.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * SHA-256 hex of the token that was handed to the client. The token itself is
     * never stored, so this column cannot be turned back into a usable credential.
     */
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    /** Shared by every token descended from one login — see RefreshTokenService. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** Database-owned, same reasoning as {@link User#getCreatedAt()}. */
    @Generated(event = EventType.INSERT)
    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** Null while live. Set on rotation, logout, or family revocation. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public RefreshToken() {
    }

    public boolean isLive(OffsetDateTime at) {
        return revokedAt == null && expiresAt.isAfter(at);
    }
}
