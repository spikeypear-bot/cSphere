package com.example.connect_sphere.attendee;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity always comes from the authenticated token; clients cannot select another attendee. */
@RestController
@RequestMapping("/api/attendee/registrations")
public class AttendeeRegistrationController {
    private final AttendeeRegistrationQueryService registrations;

    public AttendeeRegistrationController(AttendeeRegistrationQueryService registrations) {
        this.registrations = registrations;
    }

    @GetMapping
    public List<AttendeeRegistrationDetailsDto> list(Authentication authentication) {
        return registrations.list(UUID.fromString(authentication.getName()));
    }

    @GetMapping("/{registrationId}")
    public AttendeeRegistrationDetailsDto get(@PathVariable UUID registrationId, Authentication authentication) {
        return registrations.get(UUID.fromString(authentication.getName()), registrationId);
    }
}
