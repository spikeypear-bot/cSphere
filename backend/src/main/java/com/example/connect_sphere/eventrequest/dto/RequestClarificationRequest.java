package com.example.connect_sphere.eventrequest.dto;

import java.util.List;

/** EC01: what the coordinator needs, and optionally which fields it is about
 * (keys match SaveEventRequestRequest, so the organiser's form can highlight
 * them directly). */
public record RequestClarificationRequest(String message, List<String> flaggedFields) {
}
