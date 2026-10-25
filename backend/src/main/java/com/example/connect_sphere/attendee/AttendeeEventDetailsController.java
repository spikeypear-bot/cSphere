package com.example.connect_sphere.attendee;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendee/events")
public class AttendeeEventDetailsController {
    private final AttendeeEventDetailsService service;

    public AttendeeEventDetailsController(AttendeeEventDetailsService service) {
        this.service = service;
    }

    @GetMapping("/{eventId}")
    public AttendeeEventDetailsDto get(@PathVariable UUID eventId) {
        return service.get(eventId);
    }
}
