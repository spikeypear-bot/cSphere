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
    /** EO03 (V31): an organiser asked for a change; sent to the coordinator, or the Leads if unassigned. */
    change_request_submitted,
    /** EO03 (V31): the organiser withdrew it. */
    change_request_withdrawn,
    /** EC-NEW3 (V31): the coordinator approved or rejected it; sent to the organiser. */
    change_request_decided
}
