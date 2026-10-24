package com.example.connect_sphere.equipmentreview;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.connect_sphere.changerequest.ChangeApproved;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestSubmitted;
import com.example.connect_sphere.notification.service.NotificationService;

/**
 * TS11: tells Technical Support about new equipment requests (AC1) and
 * approved changes that affect equipment (AC4). Both run only after the
 * coordinator's action commits (AC5), and a failure here is logged, never
 * passed back: the request or approval has already been saved and must stand.
 *
 * Anything else that needs to react to an approved change (VS17 for Venue
 * Staff) can listen for the same {@link ChangeApproved} event.
 */
@Service
public class TechnicalSupportNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalSupportNotificationService.class);

    private final EquipmentNoticeService notices;
    private final NotificationService notifications;

    public TechnicalSupportNotificationService(EquipmentNoticeService notices, NotificationService notifications) {
        this.notices = notices;
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEquipmentRequestSubmitted(EquipmentRequestSubmitted submitted) {
        try {
            notices.describeRequest(submitted.requestId()).ifPresent(notice -> {
                List<UUID> technicians = notices.technicians();
                notifications.createEquipmentRequestSubmittedNotifications(technicians, notice.referenceId(),
                        notice.eventId(), notice.eventName(), notice.message());
            });
        } catch (RuntimeException ex) {
            log.error("Could not tell Technical Support about equipment request {}; the request itself was saved.",
                    submitted.requestId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChangeApproved(ChangeApproved approved) {
        try {
            notices.openReview(approved).ifPresent(notice -> notifications.createEquipmentChangeReviewNotifications(
                    notices.technicians(), notice.referenceId(), notice.eventId(), notice.eventName(),
                    notice.message()));
        } catch (RuntimeException ex) {
            // Includes a second delivery of the same change, which the unique
            // index refuses (AC15).
            log.error("Could not open a Technical Support review for change {}; the approval itself was saved.",
                    approved.changeRequestId(), ex);
        }
    }
}
