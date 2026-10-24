package com.example.connect_sphere.eventrequest.dto;

import java.util.List;
import java.util.Map;

import com.example.connect_sphere.activity.dto.ActivityDto;

/** EC02's review screen in one call: the request, whether it would pass the
 * submission checks right now (so Approve can explain itself before it is
 * pressed), and its full timeline. {@code scheduleProblems} (EO-C2) says
 * why the date, time or attendance would block approval, per field. */
public record EventRequestReviewDto(
        EventRequestDto request,
        List<String> missingFields,
        boolean scheduleValid,
        Map<String, String> scheduleProblems,
        List<ActivityDto> timeline) {
}
