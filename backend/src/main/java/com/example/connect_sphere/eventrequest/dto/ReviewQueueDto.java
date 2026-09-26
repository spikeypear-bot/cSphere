package com.example.connect_sphere.eventrequest.dto;

import java.util.List;

/** EC02's queue, split the way a coordinator works through it: what needs my
 * decision now, what I am waiting on the organiser for, and what nobody has
 * picked up yet. Requests assigned to other coordinators are not included. */
public record ReviewQueueDto(
        List<EventRequestDto> needsReview,
        List<EventRequestDto> awaitingOrganiser,
        List<EventRequestDto> unassigned) {
}
