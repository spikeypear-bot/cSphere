package com.example.connect_sphere.event.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.dto.EventReadinessDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.VenueBecameUnavailable;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.hold.VenueHoldService;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EC08 and EC-NEW2: every change of an event's status from Planning onwards
 * goes through here, so the rules for "ready", "safety review" and
 * "confirmed" live in one place and every screen agrees.
 *
 * <pre>
 *   Planning ──send──▶ Awaiting Safety Review ──approve──▶ Safety Approved ──confirm──▶ Confirmed
 *      ▲  ▲                 │        │                          │                        │
 *      │  └────withdraw─────┘        └─changes/reject─▶ Safety Changes Requested ──send──┘ (back to review)
 *      └──────────── returnToPlanning (a venue becomes unavailable, …) ◀──────────────────┘
 *
 *   any of the above ──cancel (EO07)──▶ Cancelled (final)
 * </pre>
 *
 * <p>Every state change takes the event row lock first, so two clicks (or two
 * people) at the same moment produce one change, one timeline entry and one
 * set of notifications. Notifications are sent only after the change commits.
 */
@Service
public class EventLifecycleService {

    static final int MAX_TEXT = 2000;

    private final EventRepository events;
    private final VenueBookingRecordRepository bookings;
    private final EquipmentRequestRepository equipmentRequests;
    private final EventRequestRepository eventRequests;
    private final AvailabilityService availability;
    private final ActivityService activity;
    private final NotificationService notifications;
    private final UserRepository users;
    private final JourneyStatusResolver statuses;
    private final Clock clock;
    /** EO07: releases a cancelled event's active venue holds. Null only for
     * callers built before EO07 that never cancel an event. */
    private final VenueHoldService holds;

    @Autowired
    public EventLifecycleService(EventRepository events, VenueBookingRecordRepository bookings,
            EquipmentRequestRepository equipmentRequests, EventRequestRepository eventRequests,
            AvailabilityService availability, ActivityService activity, NotificationService notifications,
            UserRepository users, JourneyStatusResolver statuses, VenueHoldService holds) {
        this(events, bookings, equipmentRequests, eventRequests, availability, activity, notifications, users,
                statuses, Clock.systemUTC(), holds);
    }

    /** Without EO07's hold release, for callers built before it. */
    public EventLifecycleService(EventRepository events, VenueBookingRecordRepository bookings,
            EquipmentRequestRepository equipmentRequests, EventRequestRepository eventRequests,
            AvailabilityService availability, ActivityService activity, NotificationService notifications,
            UserRepository users, JourneyStatusResolver statuses, Clock clock) {
        this(events, bookings, equipmentRequests, eventRequests, availability, activity, notifications, users,
                statuses, clock, null);
    }

    public EventLifecycleService(EventRepository events, VenueBookingRecordRepository bookings,
            EquipmentRequestRepository equipmentRequests, EventRequestRepository eventRequests,
            AvailabilityService availability, ActivityService activity, NotificationService notifications,
            UserRepository users, JourneyStatusResolver statuses, Clock clock, VenueHoldService holds) {
        this.holds = holds;
        this.events = events;
        this.bookings = bookings;
        this.equipmentRequests = equipmentRequests;
        this.eventRequests = eventRequests;
        this.availability = availability;
        this.activity = activity;
        this.notifications = notifications;
        this.users = users;
        this.statuses = statuses;
        this.clock = clock;
    }

    // ---- Readiness (the checklist on the coordinator's event page) ---------

    @Transactional(readOnly = true)
    public EventReadinessDto readiness(UUID coordinatorId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        return readinessOf(event);
    }

    EventReadinessDto readinessOf(Event event) {
        List<EventReadinessDto.Item> items = new ArrayList<>();
        List<String> arrangementBlockers = new ArrayList<>();
        String bookingPath = "/coordinator/events/" + event.getEventId() + "/venue-booking";
        String equipmentPath = "/coordinator/events/" + event.getEventId() + "/equipment-request";

        // Venues: at least one approved; nothing still pending or affected.
        List<VenueBookingRecord> active = bookings.findForEvent(event.getEventId()).stream()
                .filter(b -> b.getStatus() == VenueBookingStatus.pending || b.getStatus() == VenueBookingStatus.approved)
                .toList();
        long approved = active.stream().filter(b -> b.getStatus() == VenueBookingStatus.approved).count();
        long pending = active.stream().filter(b -> b.getStatus() == VenueBookingStatus.pending).count();
        long affected = active.stream().filter(b -> availability.affected(b.getBookingId())).count();
        String venueDetail;
        boolean venuesOk = approved > 0 && pending == 0 && affected == 0;
        if (active.isEmpty()) {
            venueDetail = "No venue has been booked yet.";
        } else if (affected > 0) {
            venueDetail = plural(affected, "venue has", "venues have")
                    + " become unavailable. Request a replacement.";
        } else if (pending > 0) {
            venueDetail = plural(pending, "booking is", "bookings are") + " still waiting for Venue Staff.";
        } else {
            venueDetail = plural(approved, "venue booking", "venue bookings") + " approved.";
        }
        if (!venuesOk) {
            arrangementBlockers.add(venueDetail);
        }
        items.add(new EventReadinessDto.Item("venues", "Venue bookings", venuesOk ? "complete" : "outstanding",
                venueDetail, venuesOk ? null : bookingPath));

        // Equipment: nothing still processing; at least one approved when the
        // organiser stated equipment requirements.
        List<EquipmentRequest> requests = equipmentRequests.findByEventId(event.getEventId());
        long processing = requests.stream().filter(r -> r.getStatus() == EquipmentRequestStatus.processing).count();
        long equipmentApproved = requests.stream().filter(r -> r.getStatus() == EquipmentRequestStatus.approved).count();
        boolean needsEquipment = event.getEquipmentRequirements() != null && !event.getEquipmentRequirements().isBlank();
        String equipmentState;
        String equipmentDetail;
        if (processing > 0) {
            equipmentState = "outstanding";
            equipmentDetail = plural(processing, "equipment request is", "equipment requests are")
                    + " still waiting for Technical Support.";
        } else if (needsEquipment && equipmentApproved == 0) {
            equipmentState = "outstanding";
            equipmentDetail = requests.isEmpty()
                    ? "The organiser listed equipment requirements, but no equipment has been requested."
                    : "No equipment request has been approved. Submit a new request.";
        } else if (equipmentApproved > 0) {
            equipmentState = "complete";
            equipmentDetail = plural(equipmentApproved, "equipment request", "equipment requests") + " approved.";
        } else {
            equipmentState = "not_needed";
            equipmentDetail = "No equipment is needed for this event.";
        }
        if ("outstanding".equals(equipmentState)) {
            arrangementBlockers.add(equipmentDetail);
        }
        items.add(new EventReadinessDto.Item("equipment", "Equipment", equipmentState, equipmentDetail,
                "outstanding".equals(equipmentState) ? equipmentPath : null));

        // Safety check.
        EventStatus status = event.getStatus();
        String safetyState;
        String safetyDetail;
        switch (status) {
            case awaiting_safety_review -> {
                safetyState = "waiting";
                safetyDetail = "With the Safety Officer for review.";
            }
            case safety_changes_requested -> {
                safetyState = "outstanding";
                safetyDetail = "The Safety Officer asked for changes. Address them, then send again.";
            }
            case safety_approved, confirmed, completed -> {
                safetyState = "complete";
                safetyDetail = "Safety check approved.";
            }
            default -> {
                safetyState = "outstanding";
                safetyDetail = "Not yet sent. Send the event once its venues and equipment are ready.";
            }
        }
        items.add(new EventReadinessDto.Item("safety", "Operational Safety Check", safetyState, safetyDetail, null));

        boolean datePassed = event.getEndDatetime() != null && event.getEndDatetime().isBefore(now());
        List<String> blockers = new ArrayList<>(arrangementBlockers);
        if (datePassed) {
            blockers.add(0, "This event's end date and time has already passed.");
        }
        boolean planningStage = status == EventStatus.pending || status == EventStatus.safety_changes_requested;
        boolean canSend = planningStage && blockers.isEmpty();
        boolean canConfirm = status == EventStatus.safety_approved && blockers.isEmpty();
        return new EventReadinessDto(statuses.forEvent(event), items, canSend,
                status == EventStatus.awaiting_safety_review, canConfirm, blockers, event.getSafetyFeedback(),
                datePassed);
    }

    // ---- Coordinator actions ----------------------------------------------

    @Transactional
    public EventReadinessDto sendForSafetyReview(UUID coordinatorId, UUID eventId) {
        Event event = lockedAssigned(coordinatorId, eventId);
        if (event.getStatus() != EventStatus.pending && event.getStatus() != EventStatus.safety_changes_requested) {
            throw new EventLifecycleException("Only an event in Planning can be sent for safety review (this one is "
                    + statuses.forEvent(event).label() + ").");
        }
        EventReadinessDto readiness = readinessOf(event);
        if (!readiness.blockers().isEmpty()) {
            throw new EventNotReadyException("This event is not ready for safety review yet.", readiness.blockers());
        }
        JourneyStatus from = statuses.journeyOf(event);
        event.setStatus(EventStatus.awaiting_safety_review);
        event.setSafetyFeedback(null);
        events.save(event);
        record(event, ActivityType.sent_for_safety_review, coordinatorId, null, from);
        return readinessOf(event);
    }

    @Transactional
    public EventReadinessDto withdrawFromSafetyReview(UUID coordinatorId, UUID eventId, String reason) {
        String why = requireText(reason, "Please give a reason for withdrawing the event from safety review.");
        Event event = lockedAssigned(coordinatorId, eventId);
        if (event.getStatus() != EventStatus.awaiting_safety_review) {
            throw new EventLifecycleException("Only an event awaiting safety review can be withdrawn (this one is "
                    + statuses.forEvent(event).label() + ").");
        }
        event.setStatus(EventStatus.pending);
        events.save(event);
        record(event, ActivityType.safety_review_withdrawn, coordinatorId, why, JourneyStatus.AWAITING_SAFETY_REVIEW);
        return readinessOf(event);
    }

    @Transactional
    public EventReadinessDto confirm(UUID coordinatorId, UUID eventId) {
        Event event = lockedAssigned(coordinatorId, eventId);
        if (event.getStatus() != EventStatus.safety_approved) {
            // A Confirmed event whose end has passed is stored as confirmed
            // but is Completed (EC-NEW2), so name the status people see.
            JourneyStatus journey = statuses.journeyOf(event);
            throw new EventLifecycleException(switch (journey) {
                case CONFIRMED -> "This event is already confirmed.";
                case COMPLETED -> "This event has already taken place (Completed), so it cannot be confirmed again.";
                default -> "An event can only be confirmed after the Safety Officer approves it (this one is "
                        + journey.label() + ").";
            });
        }
        // Re-check at the moment of confirming: something may have changed
        // since the Safety Officer approved.
        EventReadinessDto readiness = readinessOf(event);
        if (!readiness.blockers().isEmpty()) {
            throw new EventNotReadyException("This event can no longer be confirmed as it stands.", readiness.blockers());
        }
        OffsetDateTime now = now();
        event.setStatus(EventStatus.confirmed);
        event.setConfirmedAt(now);
        event.setConfirmedBy(coordinatorId);
        if (event.getFirstConfirmedAt() == null) {
            event.setFirstConfirmedAt(now);
        }
        Event saved = events.save(event);
        record(saved, ActivityType.event_confirmed, coordinatorId, null, JourneyStatus.SAFETY_APPROVED);

        UUID organiser = eventRequests.findByEventId(eventId).map(EventRequest::getCreatedBy).orElse(null);
        List<UUID> staff = staffInvolved(saved);
        String message = saved.getEventName() + " is confirmed for " + singaporeDate(saved.getStartDatetime()) + ".";
        afterCommit(() -> {
            if (organiser != null) {
                notifications.createStatusChangeNotification(organiser, null, saved.getEventId(),
                        saved.getEventName(), "confirmed", null);
            }
            if (!staff.isEmpty()) {
                notifications.createEventConfirmedNotifications(staff, saved.getEventId(), saved.getEventName(), message);
            }
        });
        return readinessOf(saved);
    }

    // ---- Safety Officer decisions (called by SO-C4/SO-C5/SO-C6) -------------

    public enum SafetyDecision { APPROVE, REQUEST_CHANGES, REJECT }

    /**
     * The Safety Officer's outcome for an event awaiting review. SO-C4/C5/C6
     * own the screens and endpoints; this is the one place the status moves,
     * so EC08's rules and the Safety Officer's stay consistent.
     */
    @Transactional
    public void recordSafetyDecision(UUID officerId, UUID eventId, SafetyDecision decision, String feedback) {
        String note = decision == SafetyDecision.APPROVE ? optionalText(feedback)
                : requireText(feedback, "Please explain what needs to change.");
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getStatus() != EventStatus.awaiting_safety_review) {
            throw new EventLifecycleException("Only an event awaiting safety review can receive a safety decision (this one is "
                    + statuses.forEvent(event).label() + ").");
        }
        if (decision == SafetyDecision.APPROVE) {
            event.setStatus(EventStatus.safety_approved);
            event.setSafetyApprovedAt(now());
            event.setSafetyFeedback(null);
        } else {
            event.setStatus(EventStatus.safety_changes_requested);
            event.setSafetyFeedback(note);
        }
        events.save(event);
        ActivityType type = switch (decision) {
            case APPROVE -> ActivityType.safety_approved;
            case REQUEST_CHANGES -> ActivityType.safety_changes_requested;
            case REJECT -> ActivityType.safety_rejected;
        };
        record(event, type, officerId, note, JourneyStatus.AWAITING_SAFETY_REVIEW);

        // The coordinator acts next either way: confirm, or make the changes.
        UUID coordinatorId = event.getCoordinatorId();
        if (coordinatorId != null) {
            UUID id = event.getEventId();
            String name = event.getEventName();
            afterCommit(() -> {
                if (decision == SafetyDecision.APPROVE) {
                    notifications.createSafetyApprovedNotification(coordinatorId, id, name);
                } else {
                    notifications.createReturnedToPlanningNotification(coordinatorId, id, name,
                            (decision == SafetyDecision.REJECT ? "The Safety Officer rejected the safety arrangement: "
                                    : "The Safety Officer asked for changes: ") + note);
                }
            });
        }
    }

    // ---- Automatic return to Planning ----------------------------------------

    /**
     * Something the event relied on has broken (a venue became unavailable, a
     * booking was cancelled, …). An event that was waiting on the Safety
     * Officer, cleared by them, or confirmed goes back to Planning so it is
     * re-checked; the coordinator is told why. An event already in Planning
     * (or finished) is left as it is.
     */
    @Transactional
    public boolean returnToPlanning(UUID eventId, UUID actorId, String reason) {
        Event event = events.findForUpdate(eventId).orElse(null);
        if (event == null) {
            return false;
        }
        EventStatus stored = event.getStatus();
        if (stored != EventStatus.awaiting_safety_review && stored != EventStatus.safety_approved
                && stored != EventStatus.confirmed) {
            return false;
        }
        JourneyStatus from = statuses.journeyOf(event);
        if (from == JourneyStatus.COMPLETED) {
            return false;
        }
        event.setStatus(EventStatus.pending);
        event.setSafetyApprovedAt(null);
        events.save(event);
        record(event, ActivityType.returned_to_planning, actorId, reason, from);
        UUID coordinator = event.getCoordinatorId();
        if (coordinator != null) {
            afterCommit(() -> notifications.createReturnedToPlanningNotification(
                    coordinator, eventId, event.getEventName(), reason));
        }
        return true;
    }

    /**
     * VS-C1: a venue became unavailable after these events relied on it.
     * Runs after the unavailability commits, in its own transaction, so it
     * never takes event locks inside the venue/booking locks (which booking
     * approval takes in the opposite order and would deadlock).
     */
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVenueBecameUnavailable(VenueBecameUnavailable change) {
        change.eventIds().forEach(id -> returnToPlanning(id, change.actorId(), change.reason()));
    }

    // ---- Cancellation (EO07) ---------------------------------------------------

    /** What cancelling released, for the caller's own response and tests. */
    public record Cancellation(List<UUID> cancelledBookingIds, List<UUID> staffNotified) {
    }

    /**
     * EO07 AC8: the event is cancelled. The only place {@code events.status}
     * becomes {@code cancelled}. Under the event row lock: its pending and
     * approved venue bookings are cancelled and their active holds released,
     * so those venues are free for that period again (rejected, replaced and
     * already-cancelled bookings are history and stay as they are). Equipment
     * reservations are kept as history; availability ignores a cancelled
     * event's (decision-log D27). The event row and everything else about it
     * are kept (AC11). Venue Staff whose bookings were cancelled, and
     * Technical Support when equipment had been approved, are told after the
     * change commits (AC10).
     *
     * <p>Callers check who may cancel (EO07: the assigned coordinator
     * approving the organiser's request); this checks only that the event can
     * still be cancelled, against its current state (AC12, AC13).
     */
    @Transactional
    public Cancellation cancel(UUID actorId, UUID eventId, String message) {
        if (holds == null) {
            throw new IllegalStateException("Cancelling an event needs the venue hold service.");
        }
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        JourneyStatus from = statuses.journeyOf(event);
        if (from.isFinal()) {
            throw new EventLifecycleException("This event can no longer be cancelled (it is " + from.label() + ").");
        }
        List<VenueBookingRecord> released = bookings.findForEvent(eventId).stream()
                .filter(b -> b.getStatus() == VenueBookingStatus.pending || b.getStatus() == VenueBookingStatus.approved)
                .toList();
        for (VenueBookingRecord booking : released) {
            holds.endForBooking(booking.getBookingId(), actorId);
            booking.setStatus(VenueBookingStatus.cancelled);
            bookings.save(booking);
        }
        event.setStatus(EventStatus.cancelled);
        Event saved = events.save(event);
        record(saved, ActivityType.event_cancelled, actorId, message, from);

        boolean hasApprovedEquipment = equipmentRequests.findByEventId(eventId).stream()
                .anyMatch(r -> r.getStatus() == EquipmentRequestStatus.approved);
        List<UUID> staff = Stream.concat(
                        released.isEmpty() ? Stream.<User>empty() : users.findByRole(UserRole.vs).stream(),
                        hasApprovedEquipment ? users.findByRole(UserRole.technician).stream() : Stream.<User>empty())
                .map(User::getUserId).distinct().toList();
        if (!staff.isEmpty()) {
            String name = saved.getEventName();
            String notice = name + " (" + singaporeDate(saved.getStartDatetime())
                    + ") has been cancelled. Its venue bookings and equipment are no longer needed.";
            afterCommit(() -> notifications.createEventCancelledNotifications(staff, eventId, name, notice));
        }
        return new Cancellation(released.stream().map(VenueBookingRecord::getBookingId).toList(), staff);
    }

    // ---- helpers --------------------------------------------------------------

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    /** The event's calendar date as people in Singapore read it. Stored times come back from
     * Postgres in UTC, so a plain toLocalDate() puts an event before 8 am SGT on the previous day. */
    private static LocalDate singaporeDate(OffsetDateTime time) {
        return time.atZoneSameInstant(SINGAPORE).toLocalDate();
    }

    private Event lockedAssigned(UUID coordinatorId, UUID eventId) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        requireAssigned(coordinatorId, event);
        return event;
    }

    private static void requireAssigned(UUID coordinatorId, Event event) {
        if (coordinatorId == null || !coordinatorId.equals(event.getCoordinatorId())) {
            throw new NotAssignedCoordinatorException(event.getEventId());
        }
    }

    /** Venue Staff always (an event can only be confirmed with an approved
     * booking, EC08 AC7); Technical Support when it has approved equipment.
     * Approvers are not recorded, so the whole team is told (decision-log D22). */
    private List<UUID> staffInvolved(Event event) {
        boolean hasEquipment = equipmentRequests.findByEventId(event.getEventId()).stream()
                .anyMatch(r -> r.getStatus() == EquipmentRequestStatus.approved);
        return Stream.concat(
                        users.findByRole(UserRole.vs).stream(),
                        hasEquipment ? users.findByRole(UserRole.technician).stream() : Stream.<User>empty())
                .map(User::getUserId).distinct().toList();
    }

    /** Timeline entries for events carry journey statuses (PLANNING,
     * SAFETY_APPROVED, …), the same codes every screen labels (EC-NEW2). */
    private void record(Event event, ActivityType type, UUID actorId, String message, JourneyStatus from) {
        JourneyStatus to = statuses.journeyOf(event);
        eventRequests.findByEventId(event.getEventId()).ifPresent(origin -> activity.record(
                new ActivityService.Entry(origin.getRequestId(), event.getEventId(), type, actorId, message, null,
                        from.name(), to.name())));
    }

    private static String requireText(String raw, String blankMessage) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new EventLifecycleValidationException(blankMessage);
        }
        if (text.length() > MAX_TEXT) {
            throw new EventLifecycleValidationException("This can be at most " + MAX_TEXT + " characters.");
        }
        return text;
    }

    private static String optionalText(String raw) {
        return raw == null || raw.isBlank() ? null : requireText(raw, "");
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
