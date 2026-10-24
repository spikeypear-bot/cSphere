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

    /** EC-NEW5: announces changes for the event change log (ChangeLogRecorder).
     * Setter-injected so this service does not depend on the log; absent in
     * plain unit tests, where nothing is announced. */
    private org.springframework.context.ApplicationEventPublisher changeLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChangeLog(org.springframework.context.ApplicationEventPublisher changeLog) {
        this.changeLog = changeLog;
    }

    private void announce(UUID eventId, com.example.connect_sphere.activity.entity.ActivityType type, UUID actor,
            String message) {
        if (changeLog != null) {
            changeLog.publishEvent(new com.example.connect_sphere.activity.service.EventChange(eventId, type, actor, message));
        }
    }

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
        // EO07 AC12: a cancelled or finished event takes no further equipment action.
        if (event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.cancelled
                || event.getStatus() == com.example.connect_sphere.event.entity.EventStatus.completed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This event has been cancelled or has finished, so its equipment request can no longer be reviewed.");
        }
        if (requestedStatus == EquipmentRequestStatus.approved) {
            requireAllItemsReserved(request, event.getStartDatetime().toInstant(),
                    event.getEndDatetime().toInstant());
        }

        request.decide(requestedStatus, rejectReason);
        if (requestedStatus == EquipmentRequestStatus.approved && shortfalls != null) {
            shortfalls.onRequestApproved(requestId);
        }
        announce(request.getEventId(), requestedStatus == EquipmentRequestStatus.approved
                        ? com.example.connect_sphere.activity.entity.ActivityType.equipment_approved
                        : com.example.connect_sphere.activity.entity.ActivityType.equipment_rejected, null,
                requestedStatus == EquipmentRequestStatus.approved
                        ? "Approved the equipment request"
                        : "Rejected the equipment request: " + rejectReason);
        return EquipmentRequestDetailsResponse.from(request, lines.findByIdRequestId(requestId));
    }

    /** TS06. With TS09, an event can have more than one approved request, so
     * the event's reservations must cover this request <em>and</em> what it is
     * still owed by the approved ones (their lines less any recorded
     * shortfall). With a single request this is just its own quantity. */
    private void requireAllItemsReserved(EquipmentRequest request, Instant eventStart, Instant eventEnd) {
        List<EquipmentLog> eventReservations = reservations.findByEventId(request.getEventId());
        for (EquipmentRequestLine line : lines.findByIdRequestId(request.getId())) {
            UUID equipmentId = line.getId().getEquipmentId();
            int reserved = eventReservations.stream()
                    .filter(reservation -> reservation.getEquipmentId().equals(equipmentId))
                    .filter(reservation -> !reservation.getLoanedFrom().isAfter(eventStart)
                            && !reservation.getLoanedUntil().isBefore(eventEnd))
                    .mapToInt(EquipmentLog::getQuantity)
                    .sum();
            int owedElsewhere = shortfalls == null ? 0
                    : shortfalls.need(request.getEventId(), equipmentId, null);
            int required = line.getQuantity() + owedElsewhere;
            if (reserved < required) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Cannot approve: only " + reserved + " of " + required
                                + " " + line.getEquipment().getName()
                                + " are reserved for the full event period.");
            }
        }
    }

    /** TS09: shortfalls and what approved requests are still owed. Setter-injected
     * like the change log; absent in plain unit tests (one request, no shortfall). */
    private com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setShortfalls(com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls) {
        this.shortfalls = shortfalls;
    }
}
