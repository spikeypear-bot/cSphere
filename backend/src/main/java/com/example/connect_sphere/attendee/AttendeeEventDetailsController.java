package com.example.connect_sphere.attendee;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api/attendee/events")
public class AttendeeEventDetailsController {
    private final AttendeeEventDetailsService service;
    private final AttendeeRegistrationService registrations;

    public AttendeeEventDetailsController(AttendeeEventDetailsService service, AttendeeRegistrationService registrations) {
        this.service = service;
        this.registrations = registrations;
    }

    @GetMapping
    public java.util.List<AttendeeEventListItemDto> list() {
        return service.list();
    }

    @GetMapping("/{eventId}")
    public AttendeeEventDetailsDto get(@PathVariable UUID eventId, Authentication authentication) {
        return service.get(eventId, UUID.fromString(authentication.getName()));
    }

    @PostMapping("/{eventId}/registrations")
    public AttendeeRegistrationDto register(@PathVariable UUID eventId, Authentication authentication) {
        return registrations.register(eventId, UUID.fromString(authentication.getName()));
    }
}
