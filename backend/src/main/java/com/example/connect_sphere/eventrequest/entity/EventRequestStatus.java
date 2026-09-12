package com.example.connect_sphere.eventrequest.entity;

/**
 * Mirrors the Postgres `event_request_status` enum (SCHEMA.md §2, extended by
 * V3__event_request_draft_support.sql to add `draft`). Lower_snake_case for the
 * same reason as {@link com.example.connect_sphere.common.enums.AccessibilityFeature}
 * — it must match the DB label exactly.
 *
 * "submitted for review" in the customer briefing/EO02 maps to {@link #pending} —
 * no separate "submitted" value was added; a request is either still being drafted
 * or awaiting coordinator action.
 */
public enum EventRequestStatus {
    draft,
    pending,
    approved,
    rejected,
    cancelled
}
