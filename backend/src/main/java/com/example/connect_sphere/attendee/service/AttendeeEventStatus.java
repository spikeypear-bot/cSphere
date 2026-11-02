package com.example.connect_sphere.attendee.service;

import com.example.connect_sphere.common.status.JourneyStatus;

/** Shared attendee labels for the canonical event lifecycle; never a registration state. */
public final class AttendeeEventStatus {
    private AttendeeEventStatus() { }

    public static String of(JourneyStatus journey) {
        return journey == JourneyStatus.CONFIRMED ? "confirmed"
                : journey == JourneyStatus.COMPLETED ? "completed"
                : journey == JourneyStatus.CANCELLED ? "cancelled" : "reconfirming";
    }
}
