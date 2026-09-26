package com.example.connect_sphere.eventrequest.dto;

import java.util.List;

import com.example.connect_sphere.activity.dto.ActivityDto;

/** EC02's review screen in one call: the request, whether it would pass the
 * submission checks right now (so Approve can explain itself before it is
 * pressed), and its full timeline. */
public record EventRequestReviewDto(
        EventRequestDto request,
        List<String> missingFields,
        boolean scheduleValid,
        List<ActivityDto> timeline) {
}
