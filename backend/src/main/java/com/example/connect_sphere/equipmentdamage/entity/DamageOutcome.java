package com.example.connect_sphere.equipmentdamage.entity;

/** TS09: what Technical Support decided for one affected event. */
public enum DamageOutcome {
    /** AC9: the event's other reservations still cover what it asked for. */
    COVERED,
    /** AC10: a serialised unit was swapped for another of the same kind. */
    REPLACED,
    /** AC12: the event is now short of what its approved requests asked for. */
    SHORT
}
