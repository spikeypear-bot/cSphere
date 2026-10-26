package com.example.connect_sphere.changerequest;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.changerequest.ChangeRequestDto.View;
import com.example.connect_sphere.common.web.ApiError;
import com.example.connect_sphere.user.entity.UserRole;

/**
 * EO03/EC-NEW3. Roles are decided in SecurityConfig; which rows each caller
 * may see or act on is decided in ChangeRequestService.
 */
@RestController
@RequestMapping("/api/change-requests")
public class ChangeRequestController {

    private final ChangeRequestService service;

    public ChangeRequestController(ChangeRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<View> create(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangeRequestDto.Create body) {
        if (body == null || body.requestId() == null) {
            throw new ChangeRequestException.NotFound(null);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(caller(jwt), body.requestId(), body.reason(), body.details()));
    }

    /** {@code ?requestId=} or {@code ?eventId=}: that request's history, newest
     * first; otherwise every pending one this caller may see, oldest first. */
    @GetMapping
    public List<View> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) UUID eventId) {
        if (requestId != null) {
            return service.forRequest(caller(jwt), requestId);
        }
        return eventId != null ? service.forEvent(caller(jwt), eventId) : service.pending(caller(jwt));
    }

    @GetMapping("/{id}")
    public View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(caller(jwt), id);
    }

    @PostMapping("/{id}/withdraw")
    public View withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.withdraw(caller(jwt), id);
    }

    @PostMapping("/{id}/approve")
    public View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ChangeRequestDto.Approve body) {
        return service.approve(caller(jwt), id, body == null ? List.of() : body.acknowledged());
    }

    @PostMapping("/{id}/reject")
    public View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ChangeRequestDto.Reject body) {
        return service.reject(caller(jwt), id, body == null ? null : body.reason());
    }

    @ExceptionHandler(ChangeRequestException.NotFound.class)
    public ResponseEntity<ApiError> notFound(ChangeRequestException.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("This change request could not be found."));
    }

    /** Includes the current impact, so the page can show what changed. */
    @ExceptionHandler(ChangeRequestException.ImpactChanged.class)
    public ResponseEntity<ImpactChangedError> impactChanged(ChangeRequestException.ImpactChanged ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ImpactChangedError(ex.getMessage(), ex.getImpact()));
    }

    @ExceptionHandler(ChangeRequestException.State.class)
    public ResponseEntity<ApiError> state(ChangeRequestException.State ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ex.getMessage()));
    }

    /** VS17: approval and its review alerts roll back together; the coordinator can retry. */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ApiError> persistenceFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("The change could not be saved. Refresh its details and try again."));
    }

    public record ImpactChangedError(String message, List<ChangeRequestDto.ImpactItem> impact) {
    }

    private static ChangeRequestService.Caller caller(Jwt jwt) {
        return new ChangeRequestService.Caller(UserRole.valueOf(jwt.getClaimAsString("role")),
                UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("organisation"));
    }
}
