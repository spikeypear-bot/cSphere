package com.example.connect_sphere.attendee;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity always comes from the authenticated token; clients cannot select another attendee. */
@RestController
@RequestMapping("/api/attendee/registrations")
public class AttendeeRegistrationController {
    private final AttendeeRegistrationQueryService registrations;
    private final AttendeeRegistrationService commands;

    public AttendeeRegistrationController(AttendeeRegistrationQueryService registrations, AttendeeRegistrationService commands) {
        this.registrations = registrations;
        this.commands = commands;
    }

    @GetMapping
    public List<AttendeeRegistrationDetailsDto> list(Authentication authentication) {
        return registrations.list(UUID.fromString(authentication.getName()));
    }

    @GetMapping("/{registrationId}")
    public AttendeeRegistrationDetailsDto get(@PathVariable UUID registrationId, Authentication authentication) {
        return registrations.get(UUID.fromString(authentication.getName()), registrationId);
    }

    @PostMapping("/{registrationId}/withdraw")
    public AttendeeRegistrationDetailsDto withdraw(@PathVariable UUID registrationId, Authentication authentication) {
        try {
            return commands.withdraw(registrationId, UUID.fromString(authentication.getName()));
        } catch (DataAccessException | TransactionException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Withdrawal failed for registration {}", registrationId, ex);
            throw new AttendeeRegistrationException(503,
                    "Withdrawal could not be confirmed. Refresh your registration status and try again.");
        }
    }
}
