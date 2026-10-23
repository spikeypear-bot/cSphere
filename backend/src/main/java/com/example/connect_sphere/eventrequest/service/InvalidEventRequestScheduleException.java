package com.example.connect_sphere.eventrequest.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EO-C2: the request's date, time or expected attendance cannot describe a
 * real event (it ends when or before it starts, it starts now or in the past,
 * or nobody is expected). Carries one message per offending field, so the
 * form can show each next to its field (HTTP 422).
 */
public class InvalidEventRequestScheduleException extends RuntimeException {

    private final transient Map<String, String> fieldErrors;

    public InvalidEventRequestScheduleException(Map<String, String> fieldErrors) {
        super(String.join(" ", fieldErrors.values()));
        // Field order is the form's order: start, end, attendance.
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
