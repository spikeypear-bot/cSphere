package com.example.connect_sphere.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.connect_sphere.eventrequest.service.EventRequestNotEditableException;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.IncompleteEventRequestException;
import com.example.connect_sphere.eventrequest.service.InvalidEventRequestScheduleException;
import com.example.connect_sphere.eventrequest.service.MissingOrganisationException;
import com.example.connect_sphere.venue.service.InvalidVenueException;
import com.example.connect_sphere.user.service.InvalidRefreshTokenException;
import com.example.connect_sphere.venue.service.VenueNotFoundException;

/** One place that turns domain exceptions into HTTP responses, so every
 * controller can just let them propagate. Add a handler here per new domain
 * exception rather than catching in the controller. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VenueNotFoundException.class)
    public ResponseEntity<ApiError> handleVenueNotFound(VenueNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(InvalidVenueException.class)
    public ResponseEntity<ApiError> handleInvalidVenue(InvalidVenueException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(EventRequestNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EventRequestNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(EventRequestNotEditableException.class)
    public ResponseEntity<ApiError> handleNotEditable(EventRequestNotEditableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(IncompleteEventRequestException.class)
    public ResponseEntity<ApiError> handleIncomplete(IncompleteEventRequestException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.missingFields(ex.getMessage(), ex.getMissingFields()));
    }

    @ExceptionHandler(InvalidEventRequestScheduleException.class)
    public ResponseEntity<ApiError> handleInvalidSchedule(InvalidEventRequestScheduleException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(MissingOrganisationException.class)
    public ResponseEntity<ApiError> handleMissingOrganisation(MissingOrganisationException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(ex.getMessage()));
    }

    /**
     * Every authentication failure answers identically — wrong password, unknown
     * username, blank credentials alike. Distinguishing them would let an
     * unauthenticated caller enumerate valid usernames one request at a time, so
     * the exception's own message is deliberately discarded rather than returned.
     *
     * This covers failures thrown from AuthController, which happen inside the
     * dispatcher and so reach @RestControllerAdvice. Requests rejected by the
     * filter chain before any controller runs never get here — those are shaped
     * by {@link RestAuthenticationEntryPoint}, which returns the same body.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("Invalid username or password"));
    }

    /**
     * Unknown, expired, already-rotated and revoked refresh tokens all land here
     * and all answer identically — the client's only recourse is to log in again,
     * and saying more would tell a holder of a stolen token what they have.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(ex.getMessage()));
    }

    /**
     * Denials raised by {@code @PreAuthorize} on a service method. These are
     * thrown inside the dispatcher, so they arrive here rather than at
     * {@link RestAccessDeniedHandler} — which catches only the filter chain's
     * own URL-level denials. Two entry points, deliberately one body: the
     * frontend should not be able to tell which layer said no.
     *
     * Registering this handler means such a denial never propagates out to
     * ExceptionTranslationFilter. That is safe only because
     * {@code anyRequest().authenticated()} keeps anonymous callers out of
     * controllers entirely — otherwise an anonymous denial would answer 403
     * here instead of being upgraded to a 401 by that filter.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("You do not have permission to perform this action."));
    }
}
