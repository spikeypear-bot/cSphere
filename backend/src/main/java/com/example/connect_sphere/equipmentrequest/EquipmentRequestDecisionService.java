package com.example.connect_sphere.equipmentrequest;

import com.example.connect_sphere.equipment.EquipmentLog;
import com.example.connect_sphere.equipment.EquipmentLogRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EquipmentRequestDecisionService {

    private static final int MAX_REJECTION_REASON_LENGTH = 2000;

    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final EventRepository events;
    private final EquipmentLogRepository reservations;

    public EquipmentRequestDecisionService(
            EquipmentRequestRepository requests,
            EquipmentRequestLineRepository lines,
            EventRepository events,
            EquipmentLogRepository reservations) {
        this.requests = requests;
        this.lines = lines;
        this.events = events;
        this.reservations = reservations;
    }

    @Transactional
    public EquipmentRequestDetailsResponse updateStatus(
            UUID requestId, UpdateEquipmentRequestStatus input) {
        if (input == null || input.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        EquipmentRequestStatus requestedStatus;
        try {
            requestedStatus = EquipmentRequestStatus.valueOf(input.status());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "status must be approved or rejected");
        }
        if (requestedStatus == EquipmentRequestStatus.processing) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A reviewed request cannot be returned to processing");
        }

        EquipmentRequest request = requests.findForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Equipment request not found"));
        if (request.getStatus() != EquipmentRequestStatus.processing) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only a processing equipment request can be reviewed");
        }

        String rejectReason = null;
        if (requestedStatus == EquipmentRequestStatus.rejected) {
            rejectReason = input.rejectReason() == null ? "" : input.rejectReason().strip();
            if (rejectReason.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A reason is required when rejecting an equipment request");
            }
            if (rejectReason.length() > MAX_REJECTION_REASON_LENGTH) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Rejection reason must be 2000 characters or fewer");
            }
        } else if (input.rejectReason() != null && !input.rejectReason().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A rejection reason can only be provided when rejecting");
        }

        Event event = events.findById(request.getEventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (requestedStatus == EquipmentRequestStatus.approved) {
            requireAllItemsReserved(request, event.getStartDatetime().toInstant(),
                    event.getEndDatetime().toInstant());
        }

        request.decide(requestedStatus, rejectReason);
        return EquipmentRequestDetailsResponse.from(request, lines.findByIdRequestId(requestId));
    }

    private void requireAllItemsReserved(EquipmentRequest request, Instant eventStart, Instant eventEnd) {
        List<EquipmentLog> eventReservations = reservations.findByEventId(request.getEventId());
        for (EquipmentRequestLine line : lines.findByIdRequestId(request.getId())) {
            int reserved = eventReservations.stream()
                    .filter(reservation -> reservation.getEquipmentId()
                            .equals(line.getId().getEquipmentId()))
                    .filter(reservation -> !reservation.getLoanedFrom().isAfter(eventStart)
                            && !reservation.getLoanedUntil().isBefore(eventEnd))
                    .mapToInt(EquipmentLog::getQuantity)
                    .sum();
            if (reserved < line.getQuantity()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Cannot approve: only " + reserved + " of " + line.getQuantity()
                                + " " + line.getEquipment().getName()
                                + " are reserved for the full event period.");
            }
        }
    }
}
