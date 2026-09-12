package com.example.connect_sphere.eventrequest.service;

import java.util.List;

/** Thrown by submit() when one or more submission-required fields are still
 * missing — EO02's "cannot submit while incomplete" rule, and EO01's "is
 * informed which required information is missing or invalid." */
public class IncompleteEventRequestException extends RuntimeException {

    private final List<String> missingFields;

    public IncompleteEventRequestException(List<String> missingFields) {
        super("Event request is missing required fields: " + String.join(", ", missingFields));
        this.missingFields = missingFields;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
