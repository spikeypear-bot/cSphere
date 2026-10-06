package com.example.connect_sphere.venue.dto;

import java.time.LocalTime;

public record VenueOperatingHourDto(int dayOfWeek, LocalTime openTime, LocalTime closeTime) {
}
