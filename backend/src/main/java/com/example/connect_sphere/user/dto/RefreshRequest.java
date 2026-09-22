package com.example.connect_sphere.user.dto;

/**
 * Body for {@code POST /api/auth/refresh} and {@code /api/auth/logout}.
 *
 * The token travels in the body for now because there is no frontend yet. Stage 9
 * should move it to an httpOnly, Secure, SameSite cookie so that a cross-site
 * script on the page cannot read it — and note that doing so puts CSRF back on
 * the table for these two endpoints specifically, since the browser would then
 * attach the credential automatically. Revisit SecurityConfig's csrf.disable()
 * at that point, not before.
 */
public record RefreshRequest(String refreshToken) {
}
