package com.example.connect_sphere.event.dto;

/** EO07: the body of every cancellation action, {"reason": "..."}. */
public record CancellationReasonRequest(String reason) {
}
