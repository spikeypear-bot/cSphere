package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.event.EventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment-requests")
public class EquipmentRequestController {

    private final EquipmentRequestRepository requestRepository;
    private final EquipmentRequestLineRepository lineRepository;
    private final EventRepository eventRepository;

    public EquipmentRequestController(
            EquipmentRequestRepository requestRepository,
            EquipmentRequestLineRepository lineRepository,
            EventRepository eventRepository) {
        this.requestRepository = requestRepository;
        this.lineRepository = lineRepository;
        this.eventRepository = eventRepository;
    }

    @GetMapping("/processing")
    public List<EquipmentRequestResponse> listProcessing() {
        return requestRepository.findByStatus(EquipmentRequestStatus.processing).stream()
            .map(r -> {
                var event = eventRepository.findById(r.getEventId()).orElse(null);
                String eventName = event != null ? event.getName() : "Unknown event";
                var start = event != null ? event.getStartDatetime().toInstant() : null;
                var end = event != null ? event.getEndDatetime().toInstant() : null;
                return EquipmentRequestResponse.from(r, eventName, start, end);
            })
            .toList();
}

    @GetMapping("/{requestId}/lines")
    public List<EquipmentRequestLineResponse> lines(@PathVariable UUID requestId) {
        return lineRepository.findByIdRequestId(requestId).stream()
                .map(EquipmentRequestLineResponse::from)
                .toList();
    }
}