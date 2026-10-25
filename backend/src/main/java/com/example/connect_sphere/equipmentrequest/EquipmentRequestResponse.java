package com.example.connect_sphere.equipmentrequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One equipment request in Technical Support's list (TS01). The first seven
 * fields are the original shape; the rest were added for the console (D32).
 */
public record EquipmentRequestResponse(
        UUID requestId,
        UUID eventId,
        String eventName,
        Instant eventStart,
        Instant eventEnd,
        String technicalRequirement,
        String status,
        /** The event's organisation, as recorded on the event. */
        String organisationName,
        /** Who asked: the submitting coordinator is not stored, so this is the
         * event's currently assigned coordinator; null when there is none. */
        Person requestedBy,
        /** When it was submitted; not stored yet, so always null. */
        Instant submittedAt,
        /** TS09 AC19: an additional request answering an equipment shortfall. */
        @JsonProperty("isShortfallRequest") boolean isShortfallRequest,
        List<Item> items,
        /** The event's journey status (as {@code JourneyStatus}, D22). */
        String eventStatus) {

    public record Person(UUID userId, String name) {
    }

    public record Item(UUID equipmentId, String equipmentName, int quantity) {
    }
}
