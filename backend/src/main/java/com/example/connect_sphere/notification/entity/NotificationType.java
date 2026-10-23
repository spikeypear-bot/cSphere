package com.example.connect_sphere.notification.entity;

/** Mirrors the Postgres `notification_type` enum (V12 migration). */
public enum NotificationType {
    venue_hold_expired,
    status_change,
    coordinator_assignment,
    /** EC01: sent to the owning organisation's Event Organisers. */
    clarification_requested,
    /** EO26: sent to the assigned Event Coordinator on resubmission. */
    clarification_responded,
    /** EC03: sent to Venue Staff when a booking request needs review. */
    venue_booking_requested,
    /** EC03: the coordinator cancelled a pending booking request (V14). */
    venue_booking_cancelled,
    venue_unavailable,
    /** EC08 (V22): Venue Staff / Technical Support told an event is confirmed. */
    event_confirmed,
    /** EC08 (V22): the coordinator told an event went back to Planning, and why. */
    event_returned_to_planning,
    // EC08: the Safety Officer approved; the coordinator can now confirm.
    event_safety_approved,
    venue_timing_changed,
    venue_timing_conflict,
    event_reassignment_requested,
    event_reassignment_approved,
    event_reassignment_declined,
    /** EO07 (V32): the assigned coordinator told the organiser cancelled a
     * request under review, with the reason. */
    request_cancelled,
    /** EO07 (V32): the assigned coordinator told the organiser asked for the
     * event to be cancelled, with the reason. */
    event_cancellation_requested
}
