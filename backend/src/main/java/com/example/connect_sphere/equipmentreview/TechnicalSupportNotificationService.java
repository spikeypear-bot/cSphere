package com.example.connect_sphere.equipmentreview;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.connect_sphere.changerequest.ChangeApproved;
import com.example.connect_sphere.equipment.Equipment;
import com.example.connect_sphere.equipment.EquipmentRepository;
import com.example.connect_sphere.equipment.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestSubmitted;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS11: tells Technical Support about new equipment requests (AC1) and
 * approved changes that affect equipment (AC4), and opens a review for the
 * second case. Both listener methods run only after the triggering action
 * commits (AC5), each in its own fresh transaction so it reads committed
 * data; a failure here is logged, never passed back, since the request or
 * approval has already been saved and must stand.
 *
 * <p>Anything else that needs to react to an approved change (VS17 for Venue
 * Staff) can listen for the same {@link ChangeApproved} event — this class is
 * only Technical Support's reaction to it, following the same
 * one-listener-per-reaction shape as {@code EventLifecycleService}'s own
 * reaction to a venue becoming unavailable.
 */
@Service
public class TechnicalSupportNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalSupportNotificationService.class);

    static final DateTimeFormatter SINGAPORE = DateTimeFormatter
            .ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Singapore"));

    /** What to tell Technical Support, and which request or review it is about. */
    record Notice(UUID referenceId, UUID eventId, String eventName, String message) {
    }

    private final EventRepository events;
    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final EquipmentRepository equipment;
    private final EquipmentReservationService reservations;
    private final EquipmentChangeReviewRepository reviews;
    private final UserRepository users;
    private final NotificationService notifications;
    private final TechnicalSupportNotificationService self;

    public TechnicalSupportNotificationService(EventRepository events, EquipmentRequestRepository requests,
            EquipmentRequestLineRepository lines, EquipmentRepository equipment,
            EquipmentReservationService reservations, EquipmentChangeReviewRepository reviews,
            UserRepository users, NotificationService notifications,
            @Lazy TechnicalSupportNotificationService self) {
        this.events = events;
        this.requests = requests;
        this.lines = lines;
        this.equipment = equipment;
        this.reservations = reservations;
        this.reviews = reviews;
        this.users = users;
        this.notifications = notifications;
        this.self = self;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEquipmentRequestSubmitted(EquipmentRequestSubmitted submitted) {
        try {
            self.describeRequest(submitted.requestId()).ifPresent(notice -> notifications
                    .createEquipmentRequestSubmittedNotifications(self.technicians(), notice.referenceId(),
                            notice.eventId(), notice.eventName(), notice.message()));
        } catch (RuntimeException ex) {
            log.error("Could not tell Technical Support about equipment request {}; the request itself was saved.",
                    submitted.requestId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChangeApproved(ChangeApproved approved) {
        try {
            self.openReview(approved).ifPresent(notice -> notifications.createEquipmentChangeReviewNotifications(
                    self.technicians(), notice.referenceId(), notice.eventId(), notice.eventName(), notice.message()));
        } catch (RuntimeException ex) {
            // Includes a second delivery of the same change, which the unique
            // index refuses (AC15).
            log.error("Could not open a Technical Support review for change {}; the approval itself was saved.",
                    approved.changeRequestId(), ex);
        }
    }

    /** Every Technical Support user. Approvers are not recorded, so the whole
     * team is told, as for event_confirmed and event_cancelled (D22). Called
     * through {@link #self} (never {@code this}), so this still runs in its
     * own fresh transaction — a direct self-call within the same bean would
     * bypass Spring's proxy and silently drop that guarantee. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    List<UUID> technicians() {
        return users.findByRole(UserRole.technician).stream().map(User::getUserId).toList();
    }

    /** AC1: the event, its dates and times, the coordinator, and the items and quantities requested. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Optional<Notice> describeRequest(UUID requestId) {
        return requests.findById(requestId).flatMap(request -> events.findById(request.getEventId()).map(event -> {
            List<String> items = lines.findByIdRequestId(requestId).stream()
                    .map(line -> line.getQuantity() + " × " + equipment.findById(line.getId().getEquipmentId())
                            .map(Equipment::getName).orElse("Unknown equipment"))
                    .toList();
            String notes = request.getTechnicalRequirement();
            String message = event.getEventName() + ", " + period(event.getStartDatetime(), event.getEndDatetime())
                    + " (Singapore time). Coordinator: " + coordinatorName(event.getCoordinatorId()) + ". "
                    + (items.isEmpty() ? "No itemised equipment." : "Requested: " + String.join(", ", items) + ".")
                    + (notes == null || notes.isBlank() ? "" : " Notes: " + notes);
            return new Notice(requestId, event.getEventId(), event.getEventName(), message);
        }));
    }

    /**
     * AC4-AC9: opens a review when an approved change affects an event with an
     * approved or processing equipment request, and says what changed. Empty when
     * nothing affects equipment (AC6), the event has no live request (AC7), or this
     * change already has a review (AC15).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<Notice> openReview(ChangeApproved approved) {
        if (reviews.existsByChangeRequestId(approved.changeRequestId())) {
            return Optional.empty();
        }
        Optional<Event> found = events.findById(approved.eventId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Event event = found.get();
        boolean liveRequest = requests.findByEventId(event.getEventId()).stream()
                .anyMatch(r -> r.getStatus() == EquipmentRequestStatus.approved
                        || r.getStatus() == EquipmentRequestStatus.processing);
        if (!liveRequest) {
            return Optional.empty();
        }
        Optional<EquipmentChangeImpact.Result> impact = EquipmentChangeImpact.assess(approved.updatedValues(),
                approved.changedFields(), reservations.reservationsForEvent(event.getEventId()));
        if (impact.isEmpty()) {
            return Optional.empty();
        }

        EquipmentChangeReview review = new EquipmentChangeReview();
        review.setReviewId(UUID.randomUUID());
        review.setEventId(event.getEventId());
        review.setChangeRequestId(approved.changeRequestId());
        review.setCoordinatorId(event.getCoordinatorId());
        review.setChangedAt(approved.approvedAt());
        review.setPreviousValues(EquipmentChangeImpact.compared(approved.previousValues()));
        review.setUpdatedValues(EquipmentChangeImpact.compared(approved.updatedValues()));
        review.setAffectedReservations(impact.get().uncovered().stream()
                .map(TechnicalSupportNotificationService::snapshot).toList());
        review.setRequirementsChanged(impact.get().requirementsChanged());
        reviews.saveAndFlush(review);

        return Optional.of(new Notice(review.getReviewId(), event.getEventId(), event.getEventName(),
                changeMessage(event, review, coordinatorName(event.getCoordinatorId()))));
    }

    /** AC8: event, coordinator, previous and updated values, uncovered reservations, when. */
    static String changeMessage(Event event, EquipmentChangeReview review, String coordinator) {
        List<String> changes = new ArrayList<>();
        changed(changes, "Start", review, EquipmentChangeImpact.START, true);
        changed(changes, "End", review, EquipmentChangeImpact.END, true);
        changed(changes, "Equipment requirements", review, EquipmentChangeImpact.REQUIREMENTS, false);
        StringBuilder message = new StringBuilder("An approved change to ").append(event.getEventName())
                .append(" on ").append(SINGAPORE.format(review.getChangedAt())).append(" affects its equipment. ")
                .append("Coordinator: ").append(coordinator).append(". ");
        if (!changes.isEmpty()) {
            message.append(String.join("; ", changes)).append(". ");
        }
        if (review.getAffectedReservations().isEmpty()) {
            message.append("Existing reservations still cover the event.");
        } else {
            message.append("No longer covering the event: ").append(String.join(", ",
                    review.getAffectedReservations().stream()
                            .map(TechnicalSupportNotificationService::describe).toList()))
                    .append('.');
        }
        return message.toString().strip();
    }

    private static void changed(List<String> into, String label, EquipmentChangeReview review, String field,
            boolean time) {
        Object before = review.getPreviousValues().get(field);
        Object after = review.getUpdatedValues().get(field);
        // Times compare as moments: 09:00+08:00 and 01:00Z are the same (as in ChangeFields).
        boolean same = time && before != null && after != null
                ? OffsetDateTime.parse(before.toString()).isEqual(OffsetDateTime.parse(after.toString()))
                : Objects.equals(before, after);
        if (same) {
            return;
        }
        into.add(label + ": " + shown(before, time) + " → " + shown(after, time));
    }

    private static String shown(Object value, boolean time) {
        if (value == null) {
            return "none";
        }
        return time ? SINGAPORE.format(OffsetDateTime.parse(value.toString())) : value.toString();
    }

    private static String describe(Map<String, Object> reservation) {
        Object serial = reservation.get("serialNumber");
        return reservation.get("quantity") + " × " + reservation.get("equipmentName")
                + (serial == null ? "" : " (" + serial + ")") + " reserved "
                + SINGAPORE.format(Instant.parse(reservation.get("loanedFrom").toString())) + " – "
                + SINGAPORE.format(Instant.parse(reservation.get("loanedUntil").toString()));
    }

    static Map<String, Object> snapshot(EquipmentReservationResponse r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("logId", r.logId().toString());
        s.put("equipmentName", r.equipmentName());
        s.put("quantity", r.quantity());
        s.put("serialNumber", r.serialNumber());
        s.put("loanedFrom", r.loanedFrom().toString());
        s.put("loanedUntil", r.loanedUntil().toString());
        return s;
    }

    private String coordinatorName(UUID coordinatorId) {
        return coordinatorId == null ? "not assigned"
                : users.findById(coordinatorId).map(User::getUsername).orElse("not assigned");
    }

    private static String period(OffsetDateTime start, OffsetDateTime end) {
        return SINGAPORE.format(start) + " – " + SINGAPORE.format(end);
    }
}
