package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;

import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.event.entity.Event;

/** AT08: the same current-state rules govern AT04 eligibility and the withdrawal command. */
final class AttendeeWithdrawalPolicy {
    private AttendeeWithdrawalPolicy() { }

    static String reason(AttendeeRegistration registration, Event event, JourneyStatus journey, OffsetDateTime now) {
        if ("withdrawn".equals(registration.getStatus())) return "This registration has already been withdrawn.";
        if (journey == JourneyStatus.CANCELLED || "event_cancelled".equals(registration.getStatus()))
            return "Withdrawal is unavailable because the event has been cancelled.";
        if (journey == JourneyStatus.COMPLETED) return "Withdrawal is unavailable because the event has completed.";
        if (!"confirmed".equals(registration.getStatus())) return "This registration is not active.";
        if (event.getStartDatetime() == null) return "Withdrawal availability cannot be determined without an event start time.";
        if (!now.isBefore(event.getStartDatetime())) return "Withdrawal is unavailable because the event has started.";
        return null;
    }
}
