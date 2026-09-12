package com.example.connect_sphere.eventrequest.service;

/** Thrown when the interim `X-Organisation` header (see docs/decision-log.md
 * D6a) is absent or blank — there is no other way yet to know whose event
 * requests are being asked for. */
public class MissingOrganisationException extends RuntimeException {
    public MissingOrganisationException() {
        super("X-Organisation header is required");
    }
}
