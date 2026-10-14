package com.example.connect_sphere.venuebooking.dto;

import java.math.BigDecimal;

public record UpdateBookingTimingRequest(BigDecimal setupDurationMinutes, BigDecimal turnaroundDurationMinutes,
        String timingNote, Integer previousSetupDurationMinutes, Integer previousTurnaroundDurationMinutes) {}
