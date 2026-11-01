package com.example.connect_sphere.notification.entity;

/** Mirrors the Postgres `notification_type` enum (V12 migration). */
public enum NotificationType {
    venue_suitability_changed, venue_arrangements_changed,
    venue_requirement_reviewed,
    venue_requirement_changed,
    venue_hold_expired,
    /** EC-C3 (V31): the coordinator reminded that a tentative hold expires soon. */
    venue_hold_expiring,
    status_change,
    coordinator_assignment,
    /** EC01: sent to the owning organisation's Event Organisers. */
    clarification_requested,
    /** EO26: sent to the assigned Event Coordinator on resubmission. */
    clarification_responded,
    /** EC03: sent to Venue Staff when a booking request needs review. */
    venue_booking_requested,
    venue_booking_rejected,
    venue_booking_approved,
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
    /** EO03 (V32): an organiser asked for a change; sent to the coordinator, or the Leads if unassigned. */
    change_request_submitted,
    /** EO03 (V32): the organiser withdrew it. */
    change_request_withdrawn,
    /** EC-NEW3 (V32): the coordinator approved or rejected it; sent to the organiser. */
    change_request_decided,
    /** EO07 (V33): the assigned coordinator told the organiser cancelled a
     * request under review, with the reason. */
    request_cancelled,
    /** EO07 (V33): the assigned coordinator told the organiser asked for the
     * event to be cancelled, with the reason. */
    event_cancellation_requested,
    /** EO07 (V33): the organiser told their event was cancelled as asked. */
    event_cancellation_approved,
    /** EO07 (V33): the organiser told the coordinator declined, and why. */
    event_cancellation_declined,
    /** EO07 (V33): Venue Staff / Technical Support told an event they had
     * arrangements for is cancelled. */
    event_cancelled,
    attendee_registration_confirmed,
    attendee_registration_withdrawn,
    /** TS09 (V38): the assigned coordinator told what Technical Support
     * decided for their event after equipment was reported damaged. */
    equipment_damage_outcome,
    /** TS11 (V65): Technical Support told a coordinator submitted an equipment request. */
    equipment_request_submitted,
    /** TS11 (V65): Technical Support told an approved event change affects an
     * event's equipment, and asked to review it. */
    equipment_change_review,
    /** EC-NEW3 (V53, D37): an approved change means the coordinator's
     * approved equipment request needs attention. */
    equipment_request_attention,
    /** EC-NEW4 (V54): a new request is waiting to be assigned; sent to every Lead. */
    request_submitted,
    /** EC-NEW4 (V54): the Lead assigned, or transferred, a request to this coordinator. */
    request_assigned,
    /** EC-NEW4 (V54): the Lead moved it to another coordinator; sent to the previous one. */
    request_handed_over,
    /** EC-NEW4 (V54): Technical Support approved or rejected the coordinator's equipment request. */
    equipment_request_decided,
    /** EO-C1 (V54): a confirmed event's venue changed, or it is confirmed again; for its organisers. */
    confirmed_venue_changed,
    /** AT09 (V59): an approved important event change, snapshotted for an attendee. */
    attendee_event_changed,
    /** AT09 (V59): an event cancellation and its original date/time, for an attendee. */
    attendee_event_cancelled
}
