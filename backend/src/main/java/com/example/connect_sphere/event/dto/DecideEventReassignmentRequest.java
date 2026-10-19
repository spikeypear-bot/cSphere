package com.example.connect_sphere.event.dto;

import java.util.UUID;

public record DecideEventReassignmentRequest(UUID coordinatorUserId, String reason) {
}
