package com.example.connect_sphere.notification.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationChange;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;
import com.example.connect_sphere.attendee.AttendeeRegistration;
import com.example.connect_sphere.attendee.AttendeeRegistrationRepository;

/** Database notices join the triggering transaction through a durable outbox.
 * Delivery retries after commit; transactional requirement and hold notices remain atomic. */
@Service
public class NotificationService {
    @org.springframework.beans.factory.annotation.Autowired
    private NotificationOutbox outbox;

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;

    /** EC-NEW4: resolves "who did it" from the signed-in user; optional so
     * plain unit tests need nothing extra. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.connect_sphere.user.repository.UserRepository users;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AttendeeRegistrationRepository attendeeRegistrations;

    /**
     * EC-NEW4: the only notices someone receives about their own action. An
     * attendee's confirmation is their receipt; equipment needing attention
     * (D37) is a follow-up left open by the change they approved, and stays
     * there until Technical Support re-approves it.
     */
    private static final Set<NotificationType> SELF_NOTICES = Set.of(NotificationType.attendee_registration_confirmed,
            NotificationType.attendee_registration_withdrawn,
            NotificationType.equipment_request_attention);

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    // ---- EC-NEW4: what the coordinator (and the Lead) is told ----------------

    /** A new request is waiting to be assigned: every Lead is told. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createRequestSubmittedNotifications(List<UUID> leads, UUID eventRequestId, String eventName,
            String message) {
        for (UUID lead : leads) {
            Notification n = base(lead, NotificationType.request_submitted, eventName);
            n.setEventRequestId(eventRequestId);
            n.setMessage(message);
            saveBestEffort(n);
        }
    }

    /** The Lead assigned or transferred a request (and its event) to this coordinator. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createRequestAssignedNotification(UUID coordinator, UUID eventRequestId, UUID eventId,
            String eventName, boolean transfer, String message) {
        Notification n = base(coordinator, NotificationType.request_assigned, eventName);
        n.setEventRequestId(eventRequestId);
        n.setEventId(eventId);
        n.setIsReassignment(transfer);
        n.setMessage(message);
        saveBestEffort(n);
    }

    /** The Lead moved it away: the previous coordinator's last notice about it. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createRequestHandedOverNotification(UUID previousCoordinator, UUID eventRequestId, UUID eventId,
            String eventName, String message) {
        Notification n = base(previousCoordinator, NotificationType.request_handed_over, eventName);
        n.setEventRequestId(eventRequestId);
        n.setEventId(eventId);
        n.setMessage(message);
        saveBestEffort(n);
    }

    /** Technical Support decided the coordinator's equipment request. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEquipmentRequestDecidedNotification(UUID coordinator, UUID eventId, String eventName,
            String reason, String message) {
        if (coordinator == null) return;
        Notification n = base(coordinator, NotificationType.equipment_request_decided, eventName);
        n.setEventId(eventId);
        n.setReason(reason);
        n.setMessage(message);
        saveBestEffort(n);
    }

    /** EO-C1: the organisers of a previously confirmed event are told its venue
     * arrangements changed. Callers decide whether the rules apply
     * (OrganiserVenueNotices); this writes one notice per organiser. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createOrganiserVenueChangeNotifications(List<UUID> organisers, UUID eventId, UUID bookingId,
            String eventName, String message) {
        for (UUID organiser : organisers) {
            Notification n = base(organiser, NotificationType.confirmed_venue_changed, eventName);
            n.setEventId(eventId);
            n.setVenueBookingId(bookingId);
            n.setMessage(message);
            saveBestEffort(n);
        }
    }

    /** EC-NEW4: marks read every unread notification up to {@code upTo} (what
     * the reader has seen); anything newer stays unread. Returns how many. */
    @Transactional
    public int markAllRead(UUID recipientUserId, OffsetDateTime upTo) {
        OffsetDateTime now = OffsetDateTime.now();
        return repository.markAllRead(recipientUserId, upTo == null || upTo.isAfter(now) ? now : upTo, now);
    }

    /** EO09. {@code recipientUserId} is always the request/event's creator —
     * callers resolve that, this method just records it. */
    @Transactional(propagation = Propagation.REQUIRED)
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
    @Transactional(propagation = Propagation.REQUIRED)
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
    @Transactional(propagation = Propagation.REQUIRED)
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
    @Transactional(propagation = Propagation.REQUIRED)
    public void createClarificationRespondedNotification(
            UUID coordinatorUserId, UUID eventRequestId, String eventName, String response) {
        Notification notification = base(coordinatorUserId, NotificationType.clarification_responded, eventName);
        notification.setEventRequestId(eventRequestId);
        notification.setMessage(response);
        saveBestEffort(notification);
    }

    /** EC03: tell Venue Staff a booking request is waiting for their review. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createVenueBookingRequestedNotifications(
            List<UUID> recipientUserIds, UUID bookingId, UUID eventId, String eventName) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.venue_booking_requested, eventName);
            notification.setEventId(eventId);
            notification.setVenueBookingId(bookingId);
            saveBestEffort(notification);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createVenueBookingRejectedNotification(UUID coordinator, UUID bookingId, UUID eventId,
            String eventName, String venue, String reason, String alternative) {
        if(coordinator==null) return;
        Notification notification=base(coordinator,NotificationType.venue_booking_rejected,eventName);
        notification.setEventId(eventId); notification.setVenueBookingId(bookingId);
        notification.setReason(reason); notification.setMessage("Booking at " + venue + " was rejected: " + reason
                + (alternative == null || alternative.isBlank() ? "" : ". " + alternative));
        saveBestEffort(notification);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createVenueBookingApprovedNotification(UUID coordinator, UUID bookingId, UUID eventId,
            String eventName, String venue) {
        if (coordinator==null) return;
        Notification notification=base(coordinator,NotificationType.venue_booking_approved,eventName);
        notification.setEventId(eventId); notification.setVenueBookingId(bookingId);
        notification.setMessage("Booking at " + venue + " was approved.");
        saveBestEffort(notification);
    }

    /** EC03: tell Venue Staff a pending request they may be reviewing was withdrawn. */
    @Transactional(propagation = Propagation.REQUIRED)
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
    @Transactional(propagation = Propagation.REQUIRED)
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
    @Transactional(propagation = Propagation.REQUIRED)
    public void createReturnedToPlanningNotification(UUID coordinatorId, UUID eventId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.event_returned_to_planning, eventName);
        notification.setEventId(eventId);
        notification.setReason(reason);
        notification.setMessage(reason);
        saveBestEffort(notification);
    }

    /** EC08: the Safety Officer approved, so the coordinator can confirm the event. */
    @Transactional(propagation = Propagation.REQUIRED)
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
        notification.setMessage("The tentative venue hold expired at " + expiry + ". The venue is no longer held;"
                + " the pending request is no longer reserved.");
        repository.saveAndFlush(notification);
    }

    private static final java.time.format.DateTimeFormatter SINGAPORE_TIME = java.time.format.DateTimeFormatter
            .ofPattern("d MMM yyyy, h:mm a", java.util.Locale.ENGLISH).withZone(java.time.ZoneId.of("Asia/Singapore"));

    /** EC-C3: remind the coordinator before a hold expires. Like the expiry notice it commits
     * with the hold's reminded mark, so a failed insert leaves the hold to be reminded again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createHoldExpiringNotification(UUID coordinatorId, UUID eventId, UUID bookingId, String eventName,
            String venueName, OffsetDateTime expiry) {
        var notification = base(coordinatorId, NotificationType.venue_hold_expiring, eventName);
        notification.setEventId(eventId);
        notification.setVenueBookingId(bookingId);
        notification.setMessage("The tentative hold on " + venueName + " for \"" + eventName + "\" expires on "
                + SINGAPORE_TIME.format(expiry) + " (Singapore time). Ask Venue Staff to confirm the booking before then,"
                + " or look for an alternative venue.");
        repository.saveAndFlush(notification);
    }

    /** EC-C1: tell Event Coordinator Leads that an event is awaiting transfer review. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEventReassignmentRequestedNotifications(
            List<UUID> recipientUserIds, UUID eventId, String eventName, String reason) {
        createEventReassignmentNotifications(recipientUserIds, eventId, eventName,
                NotificationType.event_reassignment_requested, reason);
    }

    /** EC-C1: tell the previous or newly assigned coordinator the Lead's decision. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEventReassignmentDecisionNotifications(
            List<UUID> recipientUserIds, UUID eventId, String eventName,
            NotificationType type, String message) {
        createEventReassignmentNotifications(recipientUserIds, eventId, eventName, type, message);
    }

    /** EO07: tell the assigned coordinator the organiser cancelled a request
     * they were reviewing, and why. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createRequestCancelledNotification(UUID coordinatorId, UUID eventRequestId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.request_cancelled, eventName);
        notification.setEventRequestId(eventRequestId);
        notification.setReason(reason);
        notification.setMessage(reason);
        saveBestEffort(notification);
    }

    /** EO07: tell the assigned coordinator the organiser asked for the event
     * to be cancelled, with the reason, for them to decide. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEventCancellationRequestedNotification(UUID coordinatorId, UUID eventId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.event_cancellation_requested, eventName);
        notification.setEventId(eventId);
        notification.setReason(reason);
        notification.setMessage(reason);
        saveBestEffort(notification);
    }

    /** EO07: tell the organiser the outcome of their cancellation request:
     * {@code event_cancellation_approved} or {@code event_cancellation_declined}
     * (the message then carries the coordinator's reason). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEventCancellationDecisionNotification(UUID organiserId, UUID eventId, String eventName,
            NotificationType type, String message) {
        Notification notification = base(organiserId, type, eventName);
        notification.setEventId(eventId);
        notification.setMessage(message);
        saveBestEffort(notification);
    }

    /** EO07: tell the staff with arrangements for an event that it is cancelled. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEventCancelledNotifications(List<UUID> recipientUserIds, UUID eventId, String eventName,
            String message) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, NotificationType.event_cancelled, eventName);
            notification.setEventId(eventId);
            notification.setMessage(message);
            saveBestEffort(notification);
        }
    }

    /** AT03: enqueue with the saved registration; the outbox delivers only after commit. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createAttendeeRegistrationConfirmation(UUID recipientUserId, UUID eventId, String eventName,
            String registrationReference, UUID registrationId) {
        Notification notification = base(recipientUserId, NotificationType.attendee_registration_confirmed,
                eventName);
        notification.setNotificationId(UUID.nameUUIDFromBytes(("attendee_registration_confirmed:" + registrationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        notification.setRegistrationId(registrationId);
        notification.setEventId(eventId);
        notification.setMessage("Your registration is confirmed. Reference: " + registrationReference);
        saveBestEffort(notification);
    }

    /** AT08: the receipt commits with the withdrawal; delivery/retries happen after commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createAttendeeWithdrawalConfirmation(UUID recipientUserId, UUID eventId, String eventName,
            String registrationReference, UUID registrationId, OffsetDateTime withdrawnAt) {
        Notification notification = base(recipientUserId, NotificationType.attendee_registration_withdrawn, eventName);
        notification.setNotificationId(UUID.nameUUIDFromBytes(("attendee_registration_withdrawn:" + registrationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        notification.setRegistrationId(registrationId);
        notification.setEventId(eventId);
        notification.setOccurredAt(withdrawnAt);
        notification.setMessage("Your registration has been withdrawn. Reference: " + registrationReference);
        saveBestEffort(notification);
    }

    /** AT09: one immutable, consolidated notice per active attendee and approved change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createAttendeeEventChangeNotifications(UUID changeRequestId, UUID eventId, String eventName,
            List<NotificationChange> changes, OffsetDateTime committedAt) {
        if (changes.isEmpty()) return;
        for (AttendeeRegistration registration : confirmedAttendees(eventId)) {
            Notification notification = base(registration.getAttendeeId(),
                    NotificationType.attendee_event_changed, eventName);
            notification.setNotificationId(stableAttendeeNoticeId(
                    "change:" + changeRequestId + ":" + registration.getAttendeeId()));
            notification.setRegistrationId(registration.getRegistrationId());
            notification.setEventId(eventId);
            notification.setOccurredAt(committedAt);
            notification.setMessage("Important event arrangements have changed.");
            notification.setNotificationChanges(List.copyOf(changes));
            saveBestEffort(notification);
        }
    }

    /** AT09 AC2: the event was confirmed again with different venues. One
     * notice per active attendee per confirmation; a retry of the same
     * confirmation is the same notice (AC20), a later one is new (AC21). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createAttendeeVenueChangeNotifications(UUID eventId, String eventName, String previousVenues,
            String updatedVenues, OffsetDateTime confirmedAt) {
        createAttendeeEventChangeNotifications(
                UUID.nameUUIDFromBytes(("venue:" + eventId + ":" + confirmedAt.toInstant())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eventId, eventName, List.of(new NotificationChange("venue", "Venue", previousVenues, updatedVenues)),
                confirmedAt);
    }

    /** AT09: cancellation notices snapshot the original event times and optional reason. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createAttendeeEventCancellationNotifications(UUID eventId, String eventName,
            OffsetDateTime originalStart, OffsetDateTime originalEnd, String reason, OffsetDateTime cancelledAt) {
        for (AttendeeRegistration registration : confirmedAttendees(eventId)) {
            Notification notification = base(registration.getAttendeeId(),
                    NotificationType.attendee_event_cancelled, eventName);
            notification.setNotificationId(stableAttendeeNoticeId(
                    "cancelled:" + eventId + ":" + registration.getAttendeeId()));
            notification.setRegistrationId(registration.getRegistrationId());
            notification.setEventId(eventId);
            notification.setOccurredAt(cancelledAt);
            notification.setReason(reason);
            notification.setMessage("This event has been cancelled.");
            notification.setOriginalEventStartDatetime(originalStart);
            notification.setOriginalEventEndDatetime(originalEnd);
            saveBestEffort(notification);
        }
    }

    private List<AttendeeRegistration> confirmedAttendees(UUID eventId) {
        if (attendeeRegistrations == null) {
            throw new IllegalStateException("Attendee notifications need the registration repository.");
        }
        return attendeeRegistrations.findByEventIdAndStatusAndAttendeeIdIsNotNull(eventId, "confirmed");
    }

    private static UUID stableAttendeeNoticeId(String key) {
        return UUID.nameUUIDFromBytes(("at09:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** TS09 AC16: tell the assigned coordinator the outcome for their event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createEquipmentDamageOutcomeNotification(UUID coordinatorId, UUID eventId, String eventName,
            String message) {
        Notification notification = base(coordinatorId, NotificationType.equipment_damage_outcome, eventName);
        notification.setEventId(eventId);
        notification.setMessage(message);
        saveBestEffort(notification);
    }

    /** EC-NEW3 (D37): an approved change flagged the event's equipment request. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createEquipmentRequestAttentionNotification(UUID coordinatorId, UUID eventId, String eventName,
            String reason) {
        Notification notification = base(coordinatorId, NotificationType.equipment_request_attention, eventName);
        notification.setEventId(eventId);
        notification.setReason(reason);
        notification.setMessage("The equipment request needs attention: " + reason);
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

    /** EO03/EC-NEW3: a change request was submitted, withdrawn or decided. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createChangeRequestNotifications(List<UUID> recipientUserIds, NotificationType type,
            UUID eventRequestId, UUID eventId, String eventName, String message) {
        for (UUID recipient : recipientUserIds) {
            Notification notification = base(recipient, type, eventName);
            notification.setEventRequestId(eventRequestId);
            notification.setEventId(eventId);
            notification.setMessage(message);
            saveBestEffort(notification);
        }
    }

    /** VS17: database notification and review commit with approval, like hold expiry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createVenueRequirementChangedNotifications(List<UUID> recipients,
            com.example.connect_sphere.changerequest.VenueRequirementReview review, String changes) {
        for (UUID recipient : recipients) {
            var notification = base(recipient, NotificationType.venue_requirement_changed, review.getEventName());
            notification.setRequirementReviewId(review.getReviewId());
            notification.setVenueBookingId(review.getBookingId());
            notification.setEventId(review.getEventId());
            notification.setCoordinatorName(review.getCoordinatorName());
            notification.setCoordinatorEmail(review.getCoordinatorEmail());
            notification.setOccurredAt(review.getApprovedAt());
            notification.setMessage("Review required for " + review.getVenueAddress() + " (venue " + review.getVenueId()
                    + ", booking " + review.getBookingId() + "). Approved at " + SINGAPORE_TIME.format(review.getApprovedAt())
                    + " (Singapore time). " + changes + ". Reserved " + SINGAPORE_TIME.format(review.getReservedStart())
                    + " to " + SINGAPORE_TIME.format(review.getReservedEnd()) + " (Singapore time), with "
                    + review.getSetupDurationMinutes() + " minutes setup and " + review.getTurnaroundDurationMinutes()
                    + " minutes turnaround. Coordinator: " + review.getCoordinatorName() + " (" + review.getCoordinatorEmail()
                    + "). Review the venue requirements; reservations have not been moved.");
            repository.saveAndFlush(notification);
        }
    }

    /** VS17: notify the current assigned coordinator in the outcome transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createVenueRequirementReviewedNotification(UUID coordinator,
            com.example.connect_sphere.changerequest.VenueRequirementReview review, String message) {
        if (coordinator == null) return;
        var n=base(coordinator,NotificationType.venue_requirement_reviewed,review.getEventName());
        n.setEventId(review.getEventId()); n.setVenueBookingId(review.getBookingId());
        n.setRequirementReviewId(review.getReviewId()); n.setMessage(message);
        repository.saveAndFlush(n);
    }

    /** EC-NEW4: the signed-in user is who did it; a scheduled job has none. */
    private void stampActor(Notification n) {
        if (n.getActorName() != null) return;
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)) {
            return;
        }
        try {
            UUID actor = UUID.fromString(jwt.getToken().getSubject());
            n.setActorUserId(actor);
            n.setActorRole(jwt.getToken().getClaimAsString("role"));
            if (users != null) users.findById(actor).ifPresent(u -> n.setActorName(u.getUsername()));
        } catch (IllegalArgumentException notAUser) {
            // A token without a user id says nothing about who acted.
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
        stampActor(notification);
        // EC-NEW4: nobody is notified about their own action (except an
        // attendee's own registration confirmation, which is the point of it).
        if (notification.getActorUserId() != null && notification.getActorUserId().equals(notification.getRecipientUserId())
                && !SELF_NOTICES.contains(notification.getType())) {
            return;
        }
        if (outbox!=null) {
            outbox.enqueue(notification);
            return;
        }
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
                n.getMessage(),
                n.getActorName(),
                n.getActorRole(),
                n.getNotificationChanges(),
                n.getOriginalEventStartDatetime(),
                n.getOriginalEventEndDatetime());
    }

    /** Each type is only ever sent to one role, so the type alone decides
     * which console the link opens in. EO09/EO19's original types keep their
     * organiser links exactly as before. */
    private static String linkPathFor(Notification n) {
        return switch (n.getType()) {
            case venue_booking_approved, venue_booking_rejected, venue_suitability_changed, venue_requirement_reviewed, venue_hold_expired, venue_hold_expiring, venue_unavailable, venue_timing_changed, venue_timing_conflict -> "/coordinator/events/" + n.getEventId() + "/venue-booking";
            case event_returned_to_planning, event_safety_approved, event_cancellation_requested, equipment_damage_outcome ->
                    "/coordinator/events/" + n.getEventId();
            case equipment_request_attention -> "/coordinator/events/" + n.getEventId() + "/equipment-request";
            // EC-NEW4: the Lead assigns from the request; the coordinator acts on
            // the event once it exists, else on the request. An old link after a
            // handover opens the "not assigned to you" notice (ECL-C7).
            case request_submitted -> "/coordinator-lead/unassigned-requests/" + n.getEventRequestId();
            case request_assigned, request_handed_over -> n.getEventId() != null
                    ? "/coordinator/events/" + n.getEventId()
                    : "/coordinator/requests/" + n.getEventRequestId();
            case equipment_request_decided -> "/coordinator/events/" + n.getEventId() + "/equipment-request";
            case event_reassignment_requested -> "/coordinator-lead/reassignment-requests";
            case event_reassignment_approved, event_reassignment_declined ->
                    "/coordinator/events/" + n.getEventId() + "/reassignment-request";
            // Sent to Venue Staff and Technical Support alike; neither has an
            // event page of its own yet, so the notification itself carries
            // the information and links nowhere.
            case event_confirmed, event_cancelled -> null;
            case attendee_registration_confirmed, attendee_registration_withdrawn -> n.getRegistrationId() == null
                    ? "/attendee/events/" + n.getEventId()
                    : "/attendee/my-registrations/" + n.getRegistrationId();
            case attendee_event_changed, attendee_event_cancelled -> "/attendee/events/" + n.getEventId();
            case event_cancellation_approved, event_cancellation_declined -> "/organiser/events/" + n.getEventId();
            // EO03/EC-NEW3: the coordinator, or the Lead while unassigned, gets the
            // same link; the page sends each reader to their own console.
            case change_request_submitted, change_request_withdrawn, change_request_decided ->
                    "/change-requests/" + n.getEventRequestId();
            case clarification_requested -> "/organiser/requests/" + n.getEventRequestId() + "/respond";
            case clarification_responded, request_cancelled -> "/coordinator/requests/" + n.getEventRequestId();
            case venue_requirement_changed, venue_booking_requested, venue_booking_cancelled -> "/venue-staff/bookings/" + n.getVenueBookingId();
            case venue_arrangements_changed, confirmed_venue_changed, status_change, coordinator_assignment -> n.getEventId() != null
                    ? "/organiser/events/" + n.getEventId()
                    : n.getEventRequestId() != null
                            ? "/organiser/requests/" + n.getEventRequestId()
                            : null;
        };
    }
}
