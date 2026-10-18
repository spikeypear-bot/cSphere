package com.example.connect_sphere.common.enums;

/**
 * Mirrors the Postgres `accessibilities` enum (see SCHEMA.md §2). Shared across
 * event requests, events, and venues. Venue/event columns retain this DB enum;
 * event requests store its labels in text[] (V4).
 *
 * Constant names are intentionally lower_snake_case, matching the Postgres enum
 * labels exactly — Hibernate's NamedEnumJdbcType (SCHEMA.md §6) sends the Java
 * enum's name() straight to the database with no case conversion, so a
 * conventional UPPER_SNAKE_CASE name here would not match any DB value.
 */
public enum AccessibilityFeature {
    accessible_parking("Accessible parking"),
    drop_off_zone("Drop-off zone"),
    public_transport("Near public transport"),
    step_free_access("Step-free access"),
    wide_doorways("Wide doorways"),
    elevators("Elevators"),
    wheelchair_support("Wheelchair support"),
    /** Explicitly no requirements for an event request, or no provisions for
     * a venue. Venue validation disallows combining this with other features;
     * an empty venue list means no provisions have been recorded. */
    none("No accessibility requirements needed");

    private final String label;

    AccessibilityFeature(String label) {
        this.label = label;
    }

    /** The wording people see (same as the frontend's ACCESSIBILITY_LABELS). */
    public String label() {
        return label;
    }

    /** The label for a stored code, or the code itself if it is unknown. */
    public static String labelOf(String code) {
        for (AccessibilityFeature feature : values()) {
            if (feature.name().equals(code)) {
                return feature.label;
            }
        }
        return code;
    }
}
