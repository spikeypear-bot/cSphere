package com.example.connect_sphere.user.dto;

/**
 * Credentials posted to {@code POST /api/auth/login} (AU02).
 *
 * Deliberately not annotated with Bean Validation: spring-boot-starter-validation
 * is not on the classpath, so {@code @NotBlank} would be silently ignored rather
 * than enforced — worse than no annotation at all. Nothing is lost by omitting it,
 * because authentication fails closed: a null or blank username resolves to no
 * account, and DaoAuthenticationProvider rejects null credentials before the
 * encoder ever sees them. Both paths end in the same 401 as a wrong password.
 */
public record LoginRequest(String username, String password) {
}
