package com.example.connect_sphere.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.user.dto.LoginRequest;
import com.example.connect_sphere.user.dto.LoginResponse;
import com.example.connect_sphere.user.dto.RefreshRequest;
import com.example.connect_sphere.user.service.RefreshTokenService;
import com.example.connect_sphere.user.service.TokenService;
import com.example.connect_sphere.user.service.UserPrincipal;

/**
 * AU02/AU05 — login, refresh and logout (docs/decision-log.md D19, stages 4-6).
 *
 * All three are permitAll in SecurityConfig, which is not an oversight: each one
 * carries its own credential in the body. Requiring a valid access token to
 * refresh an expired access token would defeat the point.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthenticationManager authenticationManager,
            TokenService tokenService,
            RefreshTokenService refreshTokenService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Failures are not caught here on purpose: every AuthenticationException
     * propagates to ApiExceptionHandler, which answers all of them identically so
     * the endpoint cannot be used to discover which usernames exist.
     *
     * The result is deliberately not placed in the SecurityContextHolder. This is
     * a stateless API — there is no session to carry it; the tokens returned here
     * are what prove identity on every subsequent call.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));

        // Safe cast: our DaoAuthenticationProvider is wired to AppUserDetailsService,
        // which only ever returns UserPrincipal.
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        return ResponseEntity.ok(respond(principal,
                refreshTokenService.issueForNewSession(principal.getUser())));
    }

    /**
     * No password is checked here — possession of an unused, unexpired refresh
     * token is the credential. The role and organisation are re-read from the
     * account rather than copied from the old token, so a change to either takes
     * effect at the next refresh instead of waiting out the session.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody RefreshRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken());
        return ResponseEntity.ok(
                respond(new UserPrincipal(rotation.user()), rotation.refreshToken()));
    }

    /**
     * Revokes the refresh token's whole family. 204 whether or not the token was
     * real — a client asking to forget its credentials is never refused, and the
     * endpoint must not report whether a token existed.
     *
     * Known limitation, inherent to stateless access tokens: any already-issued
     * access token stays valid until it expires. Logout ends the session's ability
     * to renew itself, not the current minutes of it.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        refreshTokenService.revokeSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private LoginResponse respond(UserPrincipal principal, String refreshToken) {
        return new LoginResponse(
                tokenService.issueAccessToken(principal),
                refreshToken,
                "Bearer",
                tokenService.accessTokenTtlSeconds(),
                principal.getUsername(),
                principal.getUser().getRole().name(),
                principal.getOrganisation());
    }
}
