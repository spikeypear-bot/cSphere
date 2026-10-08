package com.example.connect_sphere.eventrequest.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** ECL-C2: one Event Coordinator and the requests they hold that are still
 * under review, soonest event first. How many they hold is the size of
 * {@code requests}, which is empty for a coordinator who holds none. */
public record CoordinatorAssignmentsDto(
        UUID coordinatorId,
        String coordinatorName,
        List<AssignedRequest> requests) {

    /** A request with the date its organiser submitted it. That is not on
     * EventRequestDto: {@code updatedAt} moves on when the request is
     * assigned, sent back or resubmitted. */
    public record AssignedRequest(EventRequestDto request, OffsetDateTime submittedAt) {
    }
}
