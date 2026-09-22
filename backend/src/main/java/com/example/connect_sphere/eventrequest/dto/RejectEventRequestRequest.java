package com.example.connect_sphere.eventrequest.dto;

/** EO09: "can view the recorded rejection reason" — required, not optional;
 * EventRequestService.reject() rejects a blank one before it ever reaches an
 * Event Organiser's notification. */
public record RejectEventRequestRequest(String reason) {
}
