package com.example.connect_sphere.activity.entity;

import java.util.Set;

import com.example.connect_sphere.user.entity.UserRole;

/**
 * Every kind of entry the request timeline can hold (V13's
 * {@code event_request_activity.activity_type}), and who may see each kind.
 *
 * <p>The audience lives here, next to the type, rather than being chosen at
 * each call site: whether an Organiser may see "venue booking requested" is a
 * product rule about that kind of entry, not something each service should
 * decide separately (and drift on).
 *
 * <p><b>Adding a new kind of entry</b> (e.g. Venue Staff rejecting a booking
 * with a reason for VS04, or a Technical Support issue report for TS09): add a
 * constant with its audience, then call {@code ActivityService.record(...)}
 * from the service that performs that action, inside its own transaction. No
 * migration is needed: the column is text, and this enum is the whitelist.
 *
 * <p><b>Normal or Major (EC-NEW5).</b> {@code major} marks the kinds of change
 * that may affect arrangements already made (Week 4, Event Information
 * Management: "distinguish between information that may be edited normally
 * and important changes"). An organiser's resubmission is also Major when it
 * changes date, time, expected attendance, venue or equipment requirements
 * (Week 4, Event Change Requests); that depends on the values, so
 * {@code ActivityService} decides it per entry.
 */
public enum ActivityType {
    venue_hold_created(Set.of(UserRole.ec, UserRole.vs)),
    venue_hold_released(Set.of(UserRole.ec, UserRole.vs)),
    venue_hold_expired(Set.of(UserRole.ec, UserRole.vs)),
    venue_hold_converted(Set.of(UserRole.ec, UserRole.vs)),
    /** EO02: the organiser submitted the request for review. */
    submitted(Set.of(UserRole.eo, UserRole.ec)),
    /** ELC-C6: the Lead assigned an Event Coordinator, named in the message. */
    coordinator_assigned(Set.of(UserRole.eo, UserRole.ec)),
    /** ELC-C5: the Lead moved the request to another Event Coordinator;
     * the message names both. */
    coordinator_reassigned(Set.of(UserRole.eo, UserRole.ec), true),
    /** EC-C1: an Event Coordinator asked the Lead to transfer an active event. */
    event_reassignment_requested(Set.of(UserRole.eo, UserRole.ec)),
    /** EC-C1: the Lead approved a transfer to another coordinator. */
    event_reassignment_approved(Set.of(UserRole.eo, UserRole.ec)),
    /** EC-C1: the Lead declined a transfer request. */
    event_reassignment_declined(Set.of(UserRole.eo, UserRole.ec)),
    /** EC01: the coordinator asked for missing/unclear information. */
    clarification_requested(Set.of(UserRole.eo, UserRole.ec)),
    /** EO26: the organiser answered and resubmitted. */
    clarification_responded(Set.of(UserRole.eo, UserRole.ec)),
    /** EC02: proceed to planning; an Event now exists. */
    approved(Set.of(UserRole.eo, UserRole.ec)),
    /** EC02/EO09: rejected with a reason. */
    rejected(Set.of(UserRole.eo, UserRole.ec)),
    /** EC03: internal planning step; the organiser sees the outcome once the
     * booking is decided and the event confirmed, not every venue tried. */
    venue_booking_requested(Set.of(UserRole.ec, UserRole.vs)),
    /** EC03: the coordinator withdrew a pending booking request. */
    venue_booking_cancelled(Set.of(UserRole.ec, UserRole.vs), true),
    /** EC-NEW5/EC10: a replacement request for a booking that can no longer be used. */
    venue_booking_replaced(Set.of(UserRole.ec, UserRole.vs)),
    /** EC-NEW5/EC-C2: another venue requested for an event that already had one. */
    venue_added(Set.of(UserRole.ec, UserRole.vs), true),
    /** EC-NEW5/VS03: Venue Staff approved a booking request. */
    venue_booking_approved(Set.of(UserRole.ec, UserRole.vs)),
    /** EC-NEW5/VS04: Venue Staff rejected a booking request, with the reason. */
    venue_booking_rejected(Set.of(UserRole.ec, UserRole.vs), true),
    /** EC-NEW5/VS09: Venue Staff suggested another venue or arrangement. */
    venue_alternative_suggested(Set.of(UserRole.ec, UserRole.vs)),
    /** EC-NEW5/VS01: a venue this event relied on became unavailable. */
    venue_unavailable(Set.of(UserRole.ec, UserRole.vs), true),
    /** EC-NEW5/EC07: the coordinator requested equipment. */
    equipment_requested(Set.of(UserRole.ec)),
    /** EC-NEW5/TS06: Technical Support approved the equipment request. */
    equipment_approved(Set.of(UserRole.ec)),
    /** EC-NEW5/TS06: Technical Support rejected it, with the reason. */
    equipment_rejected(Set.of(UserRole.ec)),
    /** EC08: arrangements complete; handed to the Safety Officer. Internal. */
    sent_for_safety_review(Set.of(UserRole.ec)),
    /** EC08: the coordinator took the event back before a safety decision. */
    safety_review_withdrawn(Set.of(UserRole.ec)),
    /** SO-C4: the Operational Safety Check passed. */
    safety_approved(Set.of(UserRole.eo, UserRole.ec)),
    /** SO-C6: the Safety Officer asked for changes. Internal. */
    safety_changes_requested(Set.of(UserRole.ec), true),
    /** SO-C5: the Safety Officer rejected the safety arrangement. Internal. */
    safety_rejected(Set.of(UserRole.ec), true),
    /** EC08/EC-NEW2: something the event depended on broke (e.g. a venue became
     * unavailable), so it went back to Planning. The organiser sees that their
     * arrangements are being re-confirmed. */
    returned_to_planning(Set.of(UserRole.eo, UserRole.ec), true),
    /** EC08: the coordinator confirmed the event. */
    event_confirmed(Set.of(UserRole.eo, UserRole.ec, UserRole.vs)),
    /** EO07: the organiser cancelled their request before it was approved,
     * with the reason. Major: whoever was reviewing it stops. */
    request_cancelled(Set.of(UserRole.eo, UserRole.ec), true),
    /** EO07: the organiser asked for an approved event to be cancelled, with
     * the reason; the assigned coordinator decides. */
    event_cancellation_requested(Set.of(UserRole.eo, UserRole.ec), true),
    /** EC-NEW5/EC-NEW2: a confirmed event's end passed. Never stored: shown in
     * the log at the end time, because no one performs it. */
    event_completed(Set.of(UserRole.eo, UserRole.ec));

    private final Set<UserRole> audience;
    private final boolean major;

    ActivityType(Set<UserRole> audience) {
        this(audience, false);
    }

    ActivityType(Set<UserRole> audience, boolean major) {
        this.audience = audience;
        this.major = major;
    }

    public Set<UserRole> audience() {
        return audience;
    }

    /** EC-NEW5: always a Major change, whatever the details. */
    public boolean major() {
        return major;
    }
}
