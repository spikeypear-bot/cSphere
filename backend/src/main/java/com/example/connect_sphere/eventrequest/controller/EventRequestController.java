package com.example.connect_sphere.eventrequest.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.service.EventRequestService;

/**
 * EO01/EO02/EO15. `X-Organisation` stands in for a real account until one
 * exists (docs/decision-log.md D6a/Q2) — the frontend's "Login as Event
 * Organiser" flow asks for an organisation name and sends it on every call.
 */
@RestController
@RequestMapping("/api/event-requests")
public class EventRequestController {

    private final EventRequestService service;

    public EventRequestController(EventRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EventRequestDto> saveNewDraft(
            @RequestHeader("X-Organisation") String organisation,
            @RequestBody SaveEventRequestRequest request) {
        EventRequestDto saved = service.saveNewDraft(organisation, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public EventRequestDto updateDraft(
            @RequestHeader("X-Organisation") String organisation,
            @PathVariable("id") UUID id,
            @RequestBody SaveEventRequestRequest request) {
        return service.updateDraft(organisation, id, request);
    }

    @GetMapping("/{id}")
    public EventRequestDto get(
            @RequestHeader("X-Organisation") String organisation,
            @PathVariable("id") UUID id) {
        return service.get(organisation, id);
    }

    @GetMapping
    public List<EventRequestDto> list(@RequestHeader("X-Organisation") String organisation) {
        return service.list(organisation);
    }

    @PostMapping("/{id}/submit")
    public EventRequestDto submit(
            @RequestHeader("X-Organisation") String organisation,
            @PathVariable("id") UUID id) {
        return service.submit(organisation, id);
    }
}
