package com.example.connect_sphere.notification.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

/**
 * Backs EO09 (status-change) and EO19 (coordinator-assignment) notifications.
 *
 * The two {@code create*} methods run in their own transaction ({@code
 * REQUIRES_NEW}) and never throw past their own boundary — every failure is
 * caught and logged instead. This is what EO09/EO19's "does not prevent a
 * valid decision from being saved solely because an external
 * notification-delivery attempt fails" actually means in a single-database
 * system with no external channel wired up yet (see decision-log.md): the
 * one way a notification "attempt" can fail here is the insert itself
 * (storage full, DB blip), and that must never take the calling service's
 * own status-change commit down with it. Callers (EventRequestService,
 * EventService) call these *after* saving the status change, not before, and
 * treat them as fire-and-forget.
 *
 * {@code REQUIRES_NEW} only works because callers invoke this class as a
 * separate injected Spring bean — {@code @Transactional} is proxy-based and
 * does nothing on a call a bean makes to itself, so don't "simplify" this by
 * inlining these methods into EventRequestService/EventService or by calling
 * one of them from the other.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /** EO09. {@code recipientUserId} is always the request/event's creator —
     * callers resolve that, this method just records it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createStatusChangeNotification(
            UUID recipientUserId, UUID eventRequestId, UUID eventId,
            String eventName, String newStatus, String reason) {
        Notification notification = new Notification();
        notification.setNotificationId(UUID.randomUUID());
        notification.setRecipientUserId(recipientUserId);
        notification.setEventRequestId(eventRequestId);
        notification.setEventId(eventId);
        notification.setType(NotificationType.status_change);
        notification.setEventName(eventName);
        notification.setNewStatus(newStatus);
        notification.setReason(reason);
        notification.setOccurredAt(OffsetDateTime.now());
        saveBestEffort(notification);
    }

    /** EO19. {@code isReassignment} distinguishes an initial assignment from
     * a reassignment per the story's own AC — callers compute it by checking
     * whether a coordinator was already assigned before this call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createCoordinatorAssignmentNotification(
            UUID recipientUserId, UUID eventRequestId, UUID eventId, String eventName,
            String coordinatorName, String coordinatorEmail, boolean isReassignment) {
        Notification notification = new Notification();
        notification.setNotificationId(UUID.randomUUID());
        notification.setRecipientUserId(recipientUserId);
        notification.setEventRequestId(eventRequestId);
        notification.setEventId(eventId);
        notification.setType(NotificationType.coordinator_assignment);
        notification.setEventName(eventName);
        notification.setCoordinatorName(coordinatorName);
        notification.setCoordinatorEmail(coordinatorEmail);
        notification.setIsReassignment(isReassignment);
        notification.setOccurredAt(OffsetDateTime.now());
        saveBestEffort(notification);
    }

    /** EC01: tell every Event Organiser of the owning organisation that
     * clarification is needed, with the coordinator's message. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createClarificationRequestedNotifications(
            List<UUID> recipientUserIds, UUID eventRequestId, String eventName, String message) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.clarification_requested, eventName);
            notification.setEventRequestId(eventRequestId);
            notification.setMessage(message);
            saveBestEffort(notification);
        }
    }

    /** EO26: tell the assigned Event Coordinator the organiser has answered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createClarificationRespondedNotification(
            UUID coordinatorUserId, UUID eventRequestId, String eventName, String response) {
        Notification notification = base(coordinatorUserId, NotificationType.clarification_responded, eventName);
        notification.setEventRequestId(eventRequestId);
        notification.setMessage(response);
        saveBestEffort(notification);
    }

    /** EC03: tell Venue Staff a booking request is waiting for their review. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createVenueBookingRequestedNotifications(
            List<UUID> recipientUserIds, UUID bookingId, UUID eventId, String eventName) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.venue_booking_requested, eventName);
            notification.setEventId(eventId);
            notification.setVenueBookingId(bookingId);
            saveBestEffort(notification);
        }
    }

    /** EC03: tell Venue Staff a pending request they may be reviewing was withdrawn. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createVenueBookingCancelledNotifications(
            List<UUID> recipientUserIds, UUID bookingId, UUID eventId, String eventName, String reason) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.venue_booking_cancelled, eventName);
            notification.setEventId(eventId);
            notification.setVenueBookingId(bookingId);
            notification.setReason(reason);
            saveBestEffort(notification);
        }
    }

    /** EC08: tell the staff involved in an event's arrangements it is confirmed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createEventConfirmedNotifications(List<UUID> recipientUserIds, UUID eventId, String eventName,
            String message) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.event_confirmed, eventName);
            notification.setEventId(eventId);
            notification.setMessage(message);
            saveBestEffort(notification);
        }
    }

    /** EC08/EC-NEW2: tell the coordinator their event went back to Planning. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createReturnedToPlanningNotification(UUID coordinatorId, UUID eventId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.event_returned_to_planning, eventName);
        notification.setEventId(eventId);
        notification.setReason(reason);
        notification.setMessage(reason);
        saveBestEffort(notification);
    }

    /** EC08: the Safety Officer approved, so the coordinator can confirm the event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createSafetyApprovedNotification(UUID coordinatorId, UUID eventId, String eventName) {
        Notification notification = base(coordinatorId, NotificationType.event_safety_approved, eventName);
        notification.setEventId(eventId);
        saveBestEffort(notification);
    }

    /** Hold expiry and its notification commit together; failure is retried by the expiry job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createHoldExpiredNotification(UUID coordinatorId, UUID eventId, UUID bookingId, String name,
            OffsetDateTime expiry) {
        if (coordinatorId == null) return;
        var notification = base(coordinatorId, NotificationType.venue_hold_expired, name);
        notification.setEventId(eventId);
        notification.setVenueBookingId(bookingId);
        notification.setMessage("The tentative venue hold expired at " + expiry + ". The pending request is no longer reserved.");
        repository.saveAndFlush(notification);
    }

    /** EC-C1: tell Event Coordinator Leads that an event is awaiting transfer review. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createEventReassignmentRequestedNotifications(
            List<UUID> recipientUserIds, UUID eventId, String eventName, String reason) {
        createEventReassignmentNotifications(recipientUserIds, eventId, eventName,
                NotificationType.event_reassignment_requested, reason);
    }

    /** EC-C1: tell the previous or newly assigned coordinator the Lead's decision. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createEventReassignmentDecisionNotifications(
            List<UUID> recipientUserIds, UUID eventId, String eventName,
            NotificationType type, String message) {
        createEventReassignmentNotifications(recipientUserIds, eventId, eventName, type, message);
    }

    /** EO07: tell the assigned coordinator the organiser cancelled a request
     * they were reviewing, and why. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRequestCancelledNotification(UUID coordinatorId, UUID eventRequestId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.request_cancelled, eventName);
        notification.setEventRequestId(eventRequestId);
        notification.setReason(reason);
        notification.setMessage(reason);
        saveBestEffort(notification);
    }

    private void createEventReassignmentNotifications(
            List<UUID> recipientUserIds, UUID eventId, String eventName,
            NotificationType type, String message) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, type, eventName);
            notification.setEventId(eventId);
            notification.setMessage(message);
            saveBestEffort(notification);
        }
    }

    private static Notification base(UUID recipientUserId, NotificationType type, String eventName) {
        Notification notification = new Notification();
        notification.setNotificationId(UUID.randomUUID());
        notification.setRecipientUserId(recipientUserId);
        notification.setType(type);
        notification.setEventName(eventName);
        notification.setOccurredAt(OffsetDateTime.now());
        return notification;
    }

    private void saveBestEffort(Notification notification) {
        try {
            repository.save(notification);
        } catch (RuntimeException ex) {
            // Deliberately swallowed — see class Javadoc. The status change
            // this notification was *about* has already been committed by
            // the caller's own transaction (or is about to be, independently
            // of this one) and must not be rolled back because a
            // notification could not be written.
            log.error("Failed to save notification (recipient={}, type={}); the underlying "
                    + "status change/assignment was still recorded successfully.",
                    notification.getRecipientUserId(), notification.getType(), ex);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> list(UUID recipientUserId) {
        return repository.findByRecipientUserIdOrderByOccurredAtDesc(recipientUserId).stream()
                .map(NotificationService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientUserId) {
        return repository.countByRecipientUserIdAndReadAtIsNull(recipientUserId);
    }

    /** Marking as read is idempotent — re-marking an already-read notification
     * is a no-op, not an error, and never touches the event/request/
     * coordinator it's about (EO09/EO19's own AC). Ownership is enforced the
     * same not-found-either-way as EventRequestService: a notification
     * belonging to someone else is reported as not found, not forbidden, so
     * a caller cannot use this endpoint to enumerate other people's
     * notification ids. */
    @Transactional
    public NotificationDto markRead(UUID recipientUserId, UUID notificationId) {
        Notification notification = repository.findById(notificationId)
                .filter(n -> n.getRecipientUserId().equals(recipientUserId))
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
            repository.save(notification);
        }
        return toDto(notification);
    }

    private static NotificationDto toDto(Notification n) {
        String linkPath = linkPathFor(n);
        return new NotificationDto(
                n.getNotificationId(),
                n.getType().name(),
                n.getEventRequestId(),
                n.getEventId(),
                n.getEventName(),
                n.getNewStatus(),
                n.getReason(),
                n.getCoordinatorName(),
                n.getCoordinatorEmail(),
                n.getIsReassignment(),
                n.getOccurredAt(),
                n.getReadAt() != null,
                linkPath,
                n.getMessage());
    }

    /** Each type is only ever sent to one role, so the type alone decides
     * which console the link opens in. EO09/EO19's original types keep their
     * organiser links exactly as before. */
    private static String linkPathFor(Notification n) {
        return switch (n.getType()) {
            case venue_hold_expired, venue_unavailable, venue_timing_changed, venue_timing_conflict -> "/coordinator/events/" + n.getEventId() + "/venue-booking";
            case event_returned_to_planning, event_safety_approved -> "/coordinator/events/" + n.getEventId();
            case event_reassignment_requested -> "/coordinator-lead/reassignment-requests";
            case event_reassignment_approved, event_reassignment_declined ->
                    "/coordinator/events/" + n.getEventId() + "/reassignment-request";
            // Sent to Venue Staff and Technical Support alike; neither has an
            // event page of its own yet, so the notification itself carries
            // the information and links nowhere.
            case event_confirmed -> null;
            case clarification_requested -> "/organiser/requests/" + n.getEventRequestId() + "/respond";
            case clarification_responded, request_cancelled -> "/coordinator/requests/" + n.getEventRequestId();
            case venue_booking_requested, venue_booking_cancelled -> "/venue-staff/bookings/" + n.getVenueBookingId();
            case status_change, coordinator_assignment -> n.getEventId() != null
                    ? "/organiser/events/" + n.getEventId()
                    : n.getEventRequestId() != null
                            ? "/organiser/requests/" + n.getEventRequestId()
                            : null;
        };
    }
}
