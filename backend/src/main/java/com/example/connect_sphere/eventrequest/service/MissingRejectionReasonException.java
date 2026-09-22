package com.example.connect_sphere.eventrequest.service;

/** EO09: "can view the recorded rejection reason" only works if one was
 * actually recorded — reject() refuses a blank reason rather than silently
 * notifying the Organiser with nothing to act on. */
public class MissingRejectionReasonException extends RuntimeException {
    public MissingRejectionReasonException() {
        super("A rejection reason is required");
    }
}
