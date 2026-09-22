package com.example.connect_sphere.notification.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.service.NotificationService;

/**
 * EO09/EO19: "can view the notification", unread/read state, mark-as-read.
 * Open to any authenticated role — today only Event Organisers receive
 * anything, but nothing here is Organiser-specific, and scoping is always by
 * the caller's own user id (`sub`), never anything the request names, so
 * there is nothing role-specific to enforce.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    public List<NotificationDto> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userIdOf(jwt));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", service.unreadCount(userIdOf(jwt)));
    }

    @PostMapping("/{id}/read")
    public NotificationDto markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return service.markRead(userIdOf(jwt), id);
    }
}
