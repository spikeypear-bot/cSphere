package com.example.connect_sphere.user.dto;

/**
 * Returned by login and by refresh (D19 stages 5-6).
 *
 * `accessToken` proves identity on every call, as `Authorization: Bearer ...`,
 * and expires in minutes. `refreshToken` does nothing except buy a new access
 * token, is single-use, and is revocable server-side — refresh returns a fresh
 * one each time, and the previous one stops working the moment it is used.
 *
 * `username`, `role` and `organisation` also live in the access token's claims
 * and are repeated here so the frontend need not decode a JWT just to decide
 * which console to route a user to. They echo the verified account, never the
 * request.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String username,
        String role,
        String organisation) {
}
