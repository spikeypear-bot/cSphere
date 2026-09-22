package com.example.connect_sphere.eventrequest.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * EO01/EO02/EO15.
 *
 * The organisation every call is scoped by comes from the access token's
 * `organisation` claim, never from the request. It used to arrive in an
 * `X-Organisation` header, which made sense under D6a — there were no accounts,
 * so the client declaring its own organisation was the only option available.
 * Once D19 added real login that header became a hole rather than a stand-in:
 * the claim is signed, so tampering invalidates the token, whereas a header is
 * whatever the caller types. A valid Event Organiser token for one organisation
 * could read another organisation's requests simply by naming it.
 *
 * `hasRole('EO')` in SecurityConfig decides *whether* a caller may reach these
 * endpoints at all; the claim below decides *which rows* they see. Neither
 * substitutes for the other — see D20.
 */
@RestController
@RequestMapping("/api/event-requests")
public class EventRequestController {

    private final EventRequestService service;

    public EventRequestController(EventRequestService service) {
        this.service = service;
    }

    /**
     * A null here means this application minted a token without the claim its
     * own TokenService always sets — a server bug, not a malformed request. The
     * service's own requireOrganisation check catches it either way.
     */
    private static String organisationOf(Jwt jwt) {
        return jwt.getClaimAsString("organisation");
    }

    @PostMapping
    public ResponseEntity<EventRequestDto> saveNewDraft(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SaveEventRequestRequest request) {
        EventRequestDto saved = service.saveNewDraft(organisationOf(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public EventRequestDto updateDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @RequestBody SaveEventRequestRequest request) {
        return service.updateDraft(organisationOf(jwt), id, request);
    }

    @GetMapping("/{id}")
    public EventRequestDto get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.get(organisationOf(jwt), id);
    }

    @GetMapping
    public List<EventRequestDto> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(organisationOf(jwt));
    }

    @PostMapping("/{id}/submit")
    public EventRequestDto submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.submit(organisationOf(jwt), id);
    }
}
