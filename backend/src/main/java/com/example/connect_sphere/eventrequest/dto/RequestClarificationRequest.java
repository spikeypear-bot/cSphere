package com.example.connect_sphere.eventrequest.dto;

import java.util.List;
import java.util.Map;

/** EC01: what the coordinator needs, optionally which fields it is about
 * (keys match SaveEventRequestRequest, so the organiser's form can highlight
 * them directly), and (V14) one question per flagged field. */
public record RequestClarificationRequest(
        String message, List<String> flaggedFields, Map<String, String> fieldQuestions) {
}
