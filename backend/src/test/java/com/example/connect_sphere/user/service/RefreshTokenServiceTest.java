package com.example.connect_sphere.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.user.entity.RefreshToken;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.RefreshTokenRepository;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * D19 stage 6. The rotation/reuse rules were verified by hand against a running
 * stack when they were written; this pins them so a refactor cannot quietly
 * undo them. Family revocation in particular has no visible symptom when it
 * stops working — sessions simply survive a stolen token.
 */
@SpringBootTest
@Transactional
class RefreshTokenServiceTest {

    @Autowired RefreshTokenService service;
    @Autowired RefreshTokenRepository repository;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    private User user;

    @BeforeEach
    void seedUser() {
        user = users.findByUsername("eo1").orElseThrow();
    }

    /** Mirrors the service's private hashing so a test can find a row by its
     * raw token — and, in doing so, asserts the raw value is not what's stored. */
    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private RefreshToken row(String raw) {
        return repository.findByTokenHash(hash(raw)).orElseThrow();
    }

    @Test
    void storesOnlyAHashSoALeakedTableYieldsNothingUsable() {
        String raw = service.issueForNewSession(user);

        assertThat(repository.findByTokenHash(raw)).isEmpty();
        assertThat(row(raw).getTokenHash()).isNotEqualTo(raw).hasSize(64);
    }

    @Test
    void rotationIssuesASuccessorAndSpendsTheTokenPresented() {
        String first = service.issueForNewSession(user);

        RefreshTokenService.Rotation rotation = service.rotate(first);

        assertThat(rotation.refreshToken()).isNotEqualTo(first);
        assertThat(rotation.user().getUserId()).isEqualTo(user.getUserId());
        assertThat(row(first).getRevokedAt()).isNotNull();
        assertThat(row(rotation.refreshToken()).getRevokedAt()).isNull();
    }

    @Test
    void successorsStayInTheSameFamilySoOneLoginCanBeRevokedAsAUnit() {
        String first = service.issueForNewSession(user);
        String second = service.rotate(first).refreshToken();

        assertThat(row(second).getFamilyId()).isEqualTo(row(first).getFamilyId());
    }

    @Test
    void separateLoginsGetSeparateFamilies() {
        String a = service.issueForNewSession(user);
        String b = service.issueForNewSession(user);

        assertThat(row(a).getFamilyId()).isNotEqualTo(row(b).getFamilyId());
    }

    @Test
    void presentingARotatedTokenRevokesTheWholeFamilyIncludingItsSuccessor() {
        String first = service.issueForNewSession(user);
        String second = service.rotate(first).refreshToken();

        // The stolen-copy case: `first` was already spent, so whoever presents
        // it again is either replaying or holding a copy. We cannot tell which,
        // so the entire session dies.
        assertThatThrownBy(() -> service.rotate(first))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(row(second).getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> service.rotate(second))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutRevokesEveryLiveTokenInTheFamily() {
        String first = service.issueForNewSession(user);
        String second = service.rotate(first).refreshToken();

        service.revokeSession(second);

        assertThat(row(second).getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> service.rotate(second))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutIsSilentForAnUnknownTokenRatherThanAnOracle() {
        assertThatCode(() -> service.revokeSession("never-issued")).doesNotThrowAnyException();
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("never-issued"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void anExpiredTokenIsRejectedEvenThoughItWasNeverUsed() {
        String raw = service.issueForNewSession(user);
        // Native update because expires_at is mapped updatable = false: a token's
        // lifetime is fixed when it is issued, so the entity offers no way to
        // move it and the test must go round the mapping to age one. issued_at
        // moves too — chk_refresh_token_expiry_after_issue (V11) requires
        // expires_at > issued_at, so backdating only one of them is rejected.
        entityManager.flush();
        OffsetDateTime now = OffsetDateTime.now();
        entityManager.createNativeQuery(
                        "UPDATE refresh_tokens SET issued_at = :issued, expires_at = :expired "
                                + "WHERE token_hash = :hash")
                .setParameter("issued", now.minusDays(20))
                .setParameter("expired", now.minusMinutes(1))
                .setParameter("hash", hash(raw))
                .executeUpdate();
        entityManager.clear();

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
