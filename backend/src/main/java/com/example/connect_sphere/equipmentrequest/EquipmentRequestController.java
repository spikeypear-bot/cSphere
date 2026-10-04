package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.event.repository.EventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EquipmentRequestController {

    private final EquipmentRequestRepository requestRepository;
    private final EquipmentRequestLineRepository lineRepository;
    private final EventRepository eventRepository;
    private final EquipmentRequestService service;

    public EquipmentRequestController(
            EquipmentRequestRepository requestRepository,
            EquipmentRequestLineRepository lineRepository,
            EventRepository eventRepository,
            EquipmentRequestService service) {
        this.requestRepository = requestRepository;
        this.lineRepository = lineRepository;
        this.eventRepository = eventRepository;
        this.service = service;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/equipment-requests/processing")
    public List<EquipmentRequestResponse> listProcessing() {
        return requestRepository.findByStatus(EquipmentRequestStatus.processing).stream()
                .map(r -> {
                    var event = eventRepository.findById(r.getEventId()).orElse(null);
                    String eventName = event != null ? event.getEventName() : "Unknown event";
                    var start = event != null ? event.getStartDatetime().toInstant() : null;
                    var end = event != null ? event.getEndDatetime().toInstant() : null;
                    return EquipmentRequestResponse.from(r, eventName, start, end);
                })
                .toList();
    }

    @GetMapping("/equipment-requests/{requestId}/lines")
    public List<EquipmentRequestLineResponse> lines(@PathVariable UUID requestId) {
        return lineRepository.findByIdRequestId(requestId).stream()
                .map(EquipmentRequestLineResponse::from)
                .toList();
    }

    @GetMapping("/events/{eventId}/equipment-requests")
    public List<EquipmentRequestDetailsResponse> forEvent(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return service.listForEvent(userIdOf(jwt), eventId);
    }

    @PostMapping("/events/{eventId}/equipment-request")
    public ResponseEntity<EquipmentRequestDetailsResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId,
            @RequestBody SubmitEquipmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.submit(userIdOf(jwt), eventId, request));
    }
}