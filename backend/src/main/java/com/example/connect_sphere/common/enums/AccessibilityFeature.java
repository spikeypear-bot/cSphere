package com.example.connect_sphere.common.enums;

/**
 * Mirrors the Postgres `accessibilities` enum (see SCHEMA.md §2). Shared across
 * event requests, events, and (later) venues, since all three columns reference
 * the same DB type.
 *
 * Constant names are intentionally lower_snake_case, matching the Postgres enum
 * labels exactly — Hibernate's NamedEnumJdbcType (SCHEMA.md §6) sends the Java
 * enum's name() straight to the database with no case conversion, so a
 * conventional UPPER_SNAKE_CASE name here would not match any DB value.
 */
public enum AccessibilityFeature {
    accessible_parking,
    drop_off_zone,
    public_transport,
    step_free_access,
    wide_doorways,
    elevators,
    wheelchair_support
}
