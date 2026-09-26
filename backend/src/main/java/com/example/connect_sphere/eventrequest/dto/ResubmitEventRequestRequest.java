package com.example.connect_sphere.eventrequest.dto;

/** EO26: the organiser's reply, and (optionally) their edited details, saved
 * and resubmitted in one transaction so a refused resubmission changes
 * nothing. */
public record ResubmitEventRequestRequest(String response, SaveEventRequestRequest details) {
}
