package com.example.connect_sphere.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.connect_sphere.eventrequest.service.EventRequestNotEditableException;
import com.example.connect_sphere.eventrequest.service.EventRequestNotFoundException;
import com.example.connect_sphere.eventrequest.service.IncompleteEventRequestException;
import com.example.connect_sphere.eventrequest.service.MissingOrganisationException;
import com.example.connect_sphere.venue.service.InvalidVenueException;
import com.example.connect_sphere.venue.service.VenueNotFoundException;

/** One place that turns domain exceptions into HTTP responses, so every
 * controller can just let them propagate. Add a handler here per new domain
 * exception rather than catching in the controller. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VenueNotFoundException.class)
    public ResponseEntity<ApiError> handleVenueNotFound(VenueNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(InvalidVenueException.class)
    public ResponseEntity<ApiError> handleInvalidVenue(InvalidVenueException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(EventRequestNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EventRequestNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(EventRequestNotEditableException.class)
    public ResponseEntity<ApiError> handleNotEditable(EventRequestNotEditableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(IncompleteEventRequestException.class)
    public ResponseEntity<ApiError> handleIncomplete(IncompleteEventRequestException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.missingFields(ex.getMessage(), ex.getMissingFields()));
    }

    @ExceptionHandler(MissingOrganisationException.class)
    public ResponseEntity<ApiError> handleMissingOrganisation(MissingOrganisationException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(ex.getMessage()));
    }
}
