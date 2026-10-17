package com.example.connect_sphere.common.enums;

/** Labels match the existing PostgreSQL facilities enum exactly. */
public enum Facility {
    audio_visual_equipment("Audio-visual equipment"),
    air_conditioning("Air conditioning"),
    breakout_spaces("Breakout spaces"),
    projection("Projection"),
    stage("Stage"),
    dining_area("Dining area"),
    barbeque_pit("Barbeque pit");

    private final String label;

    Facility(String label) {
        this.label = label;
    }

    /** The wording people see (same as the frontend's venueFacilityLabels). */
    public String label() {
        return label;
    }

    /** The label for a stored code, or the code itself if it is unknown. */
    public static String labelOf(String code) {
        for (Facility facility : values()) {
            if (facility.name().equals(code)) {
                return facility.label;
            }
        }
        return code;
    }
}
