package com.example.connect_sphere.event.entity;

/**
 * Mirrors the Postgres {@code event_status} enum (SCHEMA.md §2). {@code
 * pending} was added by V12 — an event that exists (its request was approved)
 * but has not yet been confirmed, matching the domain model's own note that
 * "approval made only after venue is booked and equipment is confirmed"
 * (SCHEMA.md §1): approval alone does not make an event {@code confirmed}.
 */
public enum EventStatus {
    pending,
    confirmed,
    cancelled,
    completed
}
