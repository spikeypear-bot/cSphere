package com.example.connect_sphere.event.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Mirrors the Postgres {@code event_status} enum (SCHEMA.md §2). {@code
 * pending} was added by V12 — an event that exists (its request was approved)
 * but has not yet been confirmed, matching the domain model's own note that
 * "approval made only after venue is booked and equipment is confirmed"
 * (SCHEMA.md §1): approval alone does not make an event {@code confirmed}.
 */
public enum EventStatus {
    pending,
    /** EC08 (V22): sent to the Safety Officer for the Operational Safety Check. */
    awaiting_safety_review,
    /** SO-C5/SO-C6 (V22): the Safety Officer asked for changes or rejected the
     * safety arrangement; the coordinator revises and sends it again. */
    safety_changes_requested,
    /** SO-C4 (V22): cleared by the Safety Officer; ready for EC08's confirm. */
    safety_approved,
    confirmed,
    cancelled,
    completed;

    /** From Planning to Confirmed: going ahead and still looked after (EC09,
     * ECL-C7, EO03). Cancelled and completed events are not. */
    public static final Set<EventStatus> ACTIVE = Collections.unmodifiableSet(EnumSet.of(
            pending, awaiting_safety_review, safety_changes_requested, safety_approved, confirmed));

    /** Not yet confirmed: Planning through Safety Approved (EC08). */
    public static final Set<EventStatus> BEFORE_CONFIRMATION = Collections.unmodifiableSet(EnumSet.of(
            pending, awaiting_safety_review, safety_changes_requested, safety_approved));

    /** Sent for the safety check, cleared by it, or confirmed: a change to
     * the arrangements sends the event back to Planning (EC08). */
    public static final Set<EventStatus> IN_OR_PAST_SAFETY_CHECK = Collections.unmodifiableSet(EnumSet.of(
            awaiting_safety_review, safety_approved, confirmed));

    /** EC08: arrangements (venues, equipment) can still be changed — in
     * Planning, or after the Safety Officer asked for changes. */
    public boolean isPlanningEditable() {
        return this == pending || this == safety_changes_requested;
    }
}
