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
 */
public enum ActivityType {
    /** EO02: the organiser submitted the request for review. */
    submitted(Set.of(UserRole.eo, UserRole.ec)),
    /** EO19: an Event Coordinator took the request on. */
    coordinator_assigned(Set.of(UserRole.eo, UserRole.ec)),
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
    venue_booking_requested(Set.of(UserRole.ec, UserRole.vs));

    private final Set<UserRole> audience;

    ActivityType(Set<UserRole> audience) {
        this.audience = audience;
    }

    public Set<UserRole> audience() {
        return audience;
    }
}
