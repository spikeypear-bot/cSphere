package com.example.connect_sphere.equipmentreview;

/** TS11 AC11: Technical Support's review of an approved change's equipment impact. */
public enum ReviewOutcome {
    /** The existing reservations still suit the event as changed. */
    still_suitable,
    /** The coordinator must act (e.g. ask for more or different equipment); a note says what. */
    needs_coordinator_action
}
