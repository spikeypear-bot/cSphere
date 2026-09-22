package com.example.connect_sphere.user.dto;

import java.util.UUID;

/**
 * Returned by login and by refresh (D19 stages 5-6).
 *
 * `accessToken` proves identity on every call, as `Authorization: Bearer ...`,
 * and expires in minutes. `refreshToken` does nothing except buy a new access
 * token, is single-use, and is revocable server-side — refresh returns a fresh
 * one each time, and the previous one stops working the moment it is used.
 *
 * `userId`, `username`, `role` and `organisation` also live in the access
 * token's claims (`userId` as `sub`) and are repeated here so the frontend
 * need not decode a JWT just to decide which console to route a user to, or
 * — as of the EO09/EO19 slice — to compare "is this request assigned to me"
 * for an Event Coordinator. They echo the verified account, never the
 * request.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String username,
        String role,
        String organisation) {
}
