package com.example.connect_sphere.eventrequest.service;

/** Thrown when the caller's organisation is absent or blank. This used to mean a
 * missing `X-Organisation` header (D6a); since D20 the value comes from the
 * access token's `organisation` claim, which TokenService always sets — so it
 * now signals a server-side invariant failure rather than a malformed request,
 * and is kept as a guard against a service being called with a null scope. */
public class MissingOrganisationException extends RuntimeException {
    public MissingOrganisationException() {
        super("Authenticated caller has no organisation");
    }
}
