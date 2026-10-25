package com.example.connect_sphere.equipmentdamage.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** TS09 AC6, AC15: a damage report as Technical Support sees it, with every affected event. */
public record DamageReportDto(
        UUID reportId,
        UUID equipmentId,
        String equipmentName,
        boolean serialised,
        String serialNumber,
        int quantity,
        String description,
        Instant periodStart,
        Instant periodEnd,
        /** Kept for older clients: {@code blockStatus} is FAULTY. */
        boolean markFaulty,
        /** Kept for older clients: a FAULTY block was added on close. */
        boolean faultyMarked,
        String status,
        UUID reportedBy,
        String reportedByName,
        Instant reportedAt,
        Instant closedAt,
        /** AC15: every affected event has an outcome (and, non-serialised, stock is no longer over-committed). */
        boolean canClose,
        List<AffectedEvent> affectedEvents,
        /** V39: FAULTY, UNAVAILABLE or null — the TS03 block asked for on close. */
        String blockStatus,
        /** V39: that block was added when the report closed. */
        boolean blockApplied) {

    public record AffectedEvent(
            UUID outcomeId,
            UUID eventId,
            String eventName,
            OffsetDateTime eventStart,
            OffsetDateTime eventEnd,
            String journeyStatus,
            /** What the event's approved requests ask for of this kind, less shortfalls already recorded. */
            int requestedQuantity,
            /** Units of this kind reserved for the event's whole period right now. */
            int reservedQuantity,
            /** Serialised: the damaged unit's reservations for this event. Non-serialised: units
             * of the kind this event has reserved during the damage period. */
            int affectedQuantity,
            /** AC9: without the damaged unit, the event's reservations still cover its request. */
            boolean spareCovers,
            /** Whether the event can still be acted on (not cancelled or finished since). */
            boolean active,
            String outcome,
            int shortfall,
            int reducedBy,
            String replacementSerialNumber,
            Instant decidedAt,
            String resolution,
            String resolutionNote,
            Instant resolvedAt,
            UUID additionalRequestId,
            String additionalRequestStatus) {
    }
}
