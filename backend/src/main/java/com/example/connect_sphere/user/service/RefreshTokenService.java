package com.example.connect_sphere.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.user.entity.RefreshToken;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.RefreshTokenRepository;

/**
 * Issues, rotates and revokes refresh tokens (D19 stage 6).
 *
 * The access token is short-lived because a signed token cannot be withdrawn once
 * issued. This class is the other half of that trade: a long-lived credential
 * whose only power is minting access tokens, which lives in a table and can
 * therefore be destroyed.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits — the token is the credential, so guessing must be hopeless. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final RefreshTokenRepository repository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
            @Value("${security.jwt.refresh-token-ttl}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    /** What a successful rotation yields: who it was, and their next token. */
    public record Rotation(User user, String refreshToken) {
    }

    /** Starts a new family. Called once per successful login. */
    @Transactional
    public String issueForNewSession(User user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for its successor, single-use.
     *
     * Reuse detection: a row that exists but is already revoked means the token
     * was rotated before, so whoever just presented it either replayed a request
     * or is holding a stolen copy. Since we cannot tell which, the safe answer is
     * to revoke the whole family and make everyone log in again — rotation
     * without this check costs the same and protects far less.
     *
     * {@code noRollbackFor} is load-bearing, not tidiness: that revocation and the
     * exception happen in the same transaction, so without it the rollback would
     * quietly undo exactly the defence this method just mounted.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String presented) {
        OffsetDateTime now = OffsetDateTime.now();
        RefreshToken current = repository.findByTokenHash(hash(presented))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.getRevokedAt() != null) {
            int killed = repository.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} live token(s) in family {}",
                    current.getUser().getUserId(), killed, current.getFamilyId());
            throw new InvalidRefreshTokenException();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }

        current.setRevokedAt(now);
        return new Rotation(current.getUser(), issue(current.getUser(), current.getFamilyId()));
    }

    /**
     * Logout. Revokes the whole family rather than one token, so a session cannot
     * be resurrected from an older copy of it.
     *
     * Silent when the token is unknown: logout is not an oracle for whether a
     * token was ever real, and a client wanting to forget its credentials should
     * never be told "no".
     */
    @Transactional
    public void revokeSession(String presented) {
        repository.findByTokenHash(hash(presented))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), OffsetDateTime.now()));
    }

    /**
     * The raw token is returned and immediately forgotten — only its hash is
     * persisted, so a database leak yields nothing usable.
     */
    private String issue(User user, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setTokenId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenHash(hash(raw));
        token.setFamilyId(familyId);
        token.setExpiresAt(OffsetDateTime.now().plus(ttl));
        // issued_at is left alone — the column's DEFAULT supplies it, see the entity.
        repository.save(token);

        return raw;
    }

    /**
     * SHA-256, not BCrypt. Password hashing is deliberately slow to make guessing
     * a human-chosen secret expensive; this value is 256 random bits, so there is
     * nothing to guess and the slowness would only tax our own refresh endpoint.
     */
    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
