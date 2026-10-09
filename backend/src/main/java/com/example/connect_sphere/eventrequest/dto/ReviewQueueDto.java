package com.example.connect_sphere.eventrequest.dto;

import java.util.List;

/** EC02's queue, split the way a coordinator works through it: what needs my
 * decision now, and what I am waiting on the organiser for. Unassigned
 * requests and requests assigned to other coordinators are not included. */
public record ReviewQueueDto(
        List<EventRequestDto> needsReview,
        List<EventRequestDto> awaitingOrganiser) {
}
