package com.example.connect_sphere.user.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints access tokens for an already-authenticated account (D19 stage 5).
 *
 * This runs per login, which is why it is a service rather than part of
 * JwtConfig: the encoder is a singleton that knows how to sign, this knows what
 * to say. It never decides whether credentials are valid — AuthController has
 * already proved that before calling in here.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.access-token-ttl}") Duration ttl) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    /**
     * Claims are a snapshot, frozen until the token expires. Demote a user and
     * their existing token still asserts the old role, because verification
     * checks a signature rather than the database — that is the cost traded for
     * not querying on every request, and the reason the lifetime is minutes
     * rather than days (see security.jwt.access-token-ttl).
     *
     * Nothing secret goes in here. A JWT is signed, not encrypted: anyone holding
     * one can read every claim. Role and organisation are safe because the user
     * already knows both; a password hash or an internal note would not be.
     *
     * `sub` is the user's UUID rather than their username. The username is a
     * login identifier a person could conceivably change; the UUID is the stable
     * identity every other table already references.
     */
    public String issueAccessToken(UserPrincipal principal) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(principal.getUserId().toString())
                .claim("username", principal.getUsername())
                .claim("role", principal.getUser().getRole().name())
                .claim("organisation", principal.getOrganisation())
                .build();

        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** Seconds until a freshly issued token expires, for the login response. */
    public long accessTokenTtlSeconds() {
        return ttl.toSeconds();
    }
}
