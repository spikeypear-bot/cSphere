package com.example.connect_sphere.common.web;

import java.util.List;

/** Shared error body for every 4xx response — kept boring on purpose so the frontend's
 * API client (see frontend/src/lib/apiClient.ts) has one shape to handle. */
public record ApiError(String message, List<String> missingFields) {

    public static ApiError of(String message) {
        return new ApiError(message, null);
    }

    public static ApiError missingFields(String message, List<String> missingFields) {
        return new ApiError(message, missingFields);
    }
}
