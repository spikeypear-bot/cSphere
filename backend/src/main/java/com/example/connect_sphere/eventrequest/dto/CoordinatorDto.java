package com.example.connect_sphere.eventrequest.dto;

import java.util.UUID;

/** ELC-C6: one Event Coordinator the Lead can assign a request to. Only what
 * the picker needs; nothing else about the account leaves the server. */
public record CoordinatorDto(UUID userId, String username) {
}
