package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.event.entity.Event;

/** AT04: own registration history and current event information, explicitly separated. */
public record AttendeeRegistrationDetailsDto(
        UUID registrationId, String reference, OffsetDateTime registeredAt, String registrationStatus,
        UUID eventId, String eventName, OffsetDateTime startDatetime, OffsetDateTime endDatetime,
        String eventStatus, boolean eventDetailsAvailable, boolean withdrawalAvailable, String withdrawalReason,
        OffsetDateTime withdrawnAt) {

    static AttendeeRegistrationDetailsDto from(AttendeeRegistration registration, Event event,
            JourneyStatus journey, OffsetDateTime now) {
        String reason = AttendeeWithdrawalPolicy.reason(registration, event, journey, now);
        String registrationStatus = journey == JourneyStatus.CANCELLED && "confirmed".equals(registration.getStatus())
                ? "event_cancelled" : registration.getStatus();
        return new AttendeeRegistrationDetailsDto(registration.getRegistrationId(), registration.getRegistrationReference(),
                registration.getRegisteredAt(), registrationStatus, event.getEventId(), event.getEventName(),
                event.getStartDatetime(), event.getEndDatetime(), AttendeeEventStatus.of(journey), event.getFirstConfirmedAt() != null,
                reason == null, reason == null ? "Withdrawal is available before the event starts." : reason,
                registration.getWithdrawnAt());
    }
}
