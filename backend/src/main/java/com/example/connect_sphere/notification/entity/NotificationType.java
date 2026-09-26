package com.example.connect_sphere.notification.entity;

/** Mirrors the Postgres `notification_type` enum (V12 migration). */
public enum NotificationType {
    status_change,
    coordinator_assignment,
    /** EC01: sent to the owning organisation's Event Organisers. */
    clarification_requested,
    /** EO26: sent to the assigned Event Coordinator on resubmission. */
    clarification_responded,
    /** EC03: sent to Venue Staff when a booking request needs review. */
    venue_booking_requested,
    /** EC03: the coordinator cancelled a pending booking request (V14). */
    venue_booking_cancelled
}
