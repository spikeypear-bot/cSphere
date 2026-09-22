package com.example.connect_sphere.user.entity;

/**
 * Mirrors the Postgres `user_role` enum (SCHEMA.md §2, V2__init_tables.sql).
 * Lower_snake_case for the same reason as {@link
 * com.example.connect_sphere.eventrequest.entity.EventRequestStatus} — with
 * {@code @JdbcTypeCode(SqlTypes.NAMED_ENUM)} Hibernate matches on the constant's
 * {@code name()}, so these must equal the database labels exactly.
 *
 * These are the five application roles from the customer briefing:
 * {@link #ec} Event Coordinator, {@link #eo} Event Organiser, {@link #vs} Venue
 * Staff, {@link #attendee} Attendee, {@link #technician} Technical Support Staff.
 * "Developer"/"Scrum Master"/"Product Owner" are backlog-modelling categories,
 * not roles the software authenticates — do not add them here.
 */
public enum UserRole {
    ec,
    eo,
    vs,
    attendee,
    technician
}
