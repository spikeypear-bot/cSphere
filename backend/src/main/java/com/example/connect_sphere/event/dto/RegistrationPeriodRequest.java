package com.example.connect_sphere.event.dto;

/** ISO-8601 offset date-times; organiser input is sent with the Singapore offset. */
public record RegistrationPeriodRequest(String opensAt, String closesAt) {
}
