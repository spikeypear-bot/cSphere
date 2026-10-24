package com.example.connect_sphere.common.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Shared error body for every 4xx response — kept boring on purpose so the frontend's
 * API client (see frontend/src/lib/apiClient.ts) has one shape to handle.
 * {@code fieldErrors} (EO-C2) maps a field key to the message to show next to
 * that field; it is left out of the body when there is none. */
public record ApiError(String message, List<String> missingFields,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fieldErrors) {

    public static ApiError of(String message) {
        return new ApiError(message, null, null);
    }

    public static ApiError missingFields(String message, List<String> missingFields) {
        return new ApiError(message, missingFields, null);
    }

    public static ApiError fieldErrors(String message, Map<String, String> fieldErrors) {
        return new ApiError(message, null, fieldErrors);
    }
}
