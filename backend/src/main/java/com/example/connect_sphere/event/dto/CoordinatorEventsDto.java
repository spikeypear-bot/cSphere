package com.example.connect_sphere.event.dto;

import java.util.List;
import java.util.UUID;

/**
 * ECL-C7: one Event Coordinator and the active events they hold, for the
 * Event Coordinator Lead's oversight. {@code requestId} is the originating
 * request, which is what the Lead reassigns (ELC-C5); it is null only for an
 * event with no originating request.
 */
public record CoordinatorEventsDto(UUID coordinatorId, String coordinatorName, List<HeldEvent> events) {
    public record HeldEvent(UUID requestId, EventDto event) {
    }
}
