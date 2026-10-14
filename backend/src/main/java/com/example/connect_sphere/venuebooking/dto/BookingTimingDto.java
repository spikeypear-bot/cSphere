package com.example.connect_sphere.venuebooking.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record BookingTimingDto(int setupDurationMinutes, int turnaroundDurationMinutes,
        OffsetDateTime startDatetime, OffsetDateTime endDatetime,
        OffsetDateTime effectiveStart, OffsetDateTime effectiveEnd, String timingNote,
        List<VenueBookingConflictDto> conflicts, boolean editable) {}
