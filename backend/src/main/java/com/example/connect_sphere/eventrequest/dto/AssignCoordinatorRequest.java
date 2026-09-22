package com.example.connect_sphere.eventrequest.dto;

import java.util.UUID;

/** EO19: which Event Coordinator to assign (or reassign) to a request. */
public record AssignCoordinatorRequest(UUID coordinatorUserId) {
}
