package com.example.connect_sphere.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Shapes the 401 for requests the security filter chain rejects before any
 * controller runs — no credentials at all, or a Bearer token that is expired,
 * malformed or signed with the wrong key.
 *
 * {@link ApiExceptionHandler} cannot do this job: it is a
 * {@code @RestControllerAdvice}, so it only sees exceptions thrown inside the
 * DispatcherServlet, and these rejections happen upstream of it. Without this
 * class the caller gets Spring's default — an empty body plus a
 * {@code WWW-Authenticate} header — which the frontend's api client reads as a
 * missing {@code message} and papers over with generic copy.
 *
 * Spring's contract calls this "commence": the original meaning is "start the
 * authentication process" (redirect to a login page). For a token API there is
 * nothing to start, so it just writes the error.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        // Deliberately one message for every cause. Saying "token expired" vs.
        // "no token" vs. "bad signature" tells an attacker which half of a
        // guess was right, and the client's recourse is identical either way:
        // refresh, then log in. Same reasoning as ApiExceptionHandler's
        // AuthenticationException handler.
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // RFC 9110 requires a challenge on a 401. "Bearer" with no realm is the
        // honest one here and, unlike "Basic", does not make browsers open a
        // native credential dialog over the React app.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of("Authentication required. Please log in again."));
    }
}
