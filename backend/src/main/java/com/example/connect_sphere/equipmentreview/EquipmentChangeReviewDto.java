package com.example.connect_sphere.equipmentreview;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** TS11 read and write shapes for equipment change reviews. */
public final class EquipmentChangeReviewDto {

    private EquipmentChangeReviewDto() {
    }

    /** The compared fields before or after the change (AC8). */
    public record Values(OffsetDateTime start, OffsetDateTime end, String equipmentRequirements) {
    }

    /** A reservation; {@code flagged} while it no longer covered the event and is not yet reviewed (AC10, AC14). */
    public record Reservation(UUID logId, String equipmentName, int quantity, String serialNumber,
            Instant loanedFrom, Instant loanedUntil, boolean flagged) {
    }

    /** One row of a list: Technical Support's queue, or the coordinator's view of an event (AC12). */
    public record Summary(UUID reviewId, UUID eventId, String eventName, OffsetDateTime changedAt,
            boolean needsReview, int affectedCount, boolean requirementsChanged, String outcome, String outcomeNote,
            String reviewedByName, OffsetDateTime reviewedAt) {
    }

    /** Technical Support's review page (AC10, AC11). */
    public record Detail(UUID reviewId, UUID eventId, String eventName, OffsetDateTime eventStart,
            OffsetDateTime eventEnd, String coordinatorName, OffsetDateTime changedAt, Values previous,
            Values updated, boolean requirementsChanged, List<Reservation> affectedAtChange,
            List<Reservation> reservations, boolean needsReview, String outcome, String outcomeNote,
            String reviewedByName, OffsetDateTime reviewedAt) {
    }

    /** AC11: {@code still_suitable}, or {@code needs_coordinator_action} with a note. */
    public record OutcomeRequest(String outcome, String note) {
    }
}
