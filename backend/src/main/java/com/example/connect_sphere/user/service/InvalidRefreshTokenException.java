package com.example.connect_sphere.user.service;

/**
 * A refresh token was unknown, expired, already rotated, or revoked.
 *
 * One exception for all four on purpose: the client can do nothing differently
 * about any of them except log in again, and distinguishing them would tell an
 * attacker holding a stolen token whether it was ever valid.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
