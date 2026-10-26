package com.example.connect_sphere.equipmentrequest;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EquipmentRequestController {

    private final EquipmentRequestLineRepository lineRepository;
    private final EquipmentRequestQueueService queue;
    private final EquipmentRequestService service;
    private final EquipmentRequestDecisionService decisionService;

    public EquipmentRequestController(
            EquipmentRequestLineRepository lineRepository,
            EquipmentRequestQueueService queue,
            EquipmentRequestService service,
            EquipmentRequestDecisionService decisionService) {
        this.lineRepository = lineRepository;
        this.queue = queue;
        this.service = service;
        this.decisionService = decisionService;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // TS01: the review queue. Kept as it was; the same list as ?status=processing.
    @GetMapping("/equipment-requests/processing")
    public List<EquipmentRequestResponse> listProcessing() {
        return queue.list("processing");
    }

    // D32: the same list by status (processing, the default; approved; rejected; all).
    @GetMapping("/equipment-requests")
    public List<EquipmentRequestResponse> list(@RequestParam(required = false) String status) {
        return queue.list(status);
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
        // The token's role claim is the UserRole name (TokenService).
        // Read-only: Safety Officer review and Lead oversight (ECL-C7).
        String role = jwt.getClaimAsString("role");
        if ("safety_officer".equalsIgnoreCase(role) || "ecl".equalsIgnoreCase(role)) {
            return service.listForSafetyOfficer(eventId);
        }
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

    // EC-NEW3: the coordinator replaces the complete equipment request,
    // releasing reservations and superseding what it replaces.
    @PatchMapping("/events/{eventId}/equipment-request")
    public EquipmentRequestDetailsResponse revise(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID eventId,
            @RequestBody SubmitEquipmentRequest request) {
        return service.revise(userIdOf(jwt), eventId, request);
    }

    @PatchMapping("/equipment-requests/{requestId}/status")
    public EquipmentRequestDetailsResponse updateStatus(
            @PathVariable UUID requestId,
            @RequestBody UpdateEquipmentRequestStatus request) {
        return decisionService.updateStatus(requestId, request);
    }
}
