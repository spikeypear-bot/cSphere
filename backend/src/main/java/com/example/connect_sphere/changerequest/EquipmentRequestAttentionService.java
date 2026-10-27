package com.example.connect_sphere.changerequest;

import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.EventChange;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.notification.service.NotificationService;

/** EC-NEW3 (D37): the equipment side of VS17's live re-check. Joins the
 * approval transaction; flags approved equipment requests the change no
 * longer fits, and re-flags ones already flagged so a later change is not
 * missed. Never 'processing' ones: Technical Support's approval re-checks
 * them against the current details. */
@Service
public class EquipmentRequestAttentionService {
    public static final String COVERAGE_BROKEN = "The event's new time no longer matches an existing equipment reservation.";
    public static final String REQUIREMENTS_CHANGED = "The event's equipment requirements text changed.";

    private final EquipmentRequestRepository requests;
    private final EquipmentReservationService reservations;
    private final ApplicationEventPublisher changeLog;
    private final NotificationService notifications;

    public EquipmentRequestAttentionService(EquipmentRequestRepository requests,
            EquipmentReservationService reservations, ApplicationEventPublisher changeLog,
            NotificationService notifications) {
        this.requests = requests;
        this.reservations = reservations;
        this.changeLog = changeLog;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(ChangeRequest change, Event event, Set<String> changedFields, SaveEventRequestRequest proposed) {
        if (event == null) return;
        List<EquipmentRequest> active = requests.findByEventIdForUpdate(event.getEventId()).stream()
                .filter(r -> r.getStatus().heldByTechnicalSupport())
                .toList();
        if (active.isEmpty()) return;
        boolean timesChanged = changedFields.contains("startDatetime") || changedFields.contains("endDatetime");
        // Re-checked after the change applied, like BookingAssessmentService, not from approve()'s earlier snapshot.
        boolean coverageBroken = timesChanged && reservations.reservationsForEvent(event.getEventId()).stream()
                .anyMatch(r -> !r.covers(proposed.startDatetime().toInstant(), proposed.endDatetime().toInstant()));
        if (!coverageBroken && !changedFields.contains("equipmentRequirements")) return;
        String reason = coverageBroken ? COVERAGE_BROKEN : REQUIREMENTS_CHANGED;
        // A reservation the event no longer fits outranks a wording change, whichever came first.
        active.forEach(r -> r.flagAttention(COVERAGE_BROKEN.equals(r.getAttentionReason()) ? COVERAGE_BROKEN : reason));
        requests.saveAll(active);
        changeLog.publishEvent(new EventChange(event.getEventId(), ActivityType.equipment_request_attention,
                change.getDecidedBy(), reason));
        if (event.getCoordinatorId() != null) {
            notifications.createEquipmentRequestAttentionNotification(event.getCoordinatorId(), event.getEventId(),
                    event.getEventName(), reason);
        }
    }
}
