package com.example.connect_sphere.attendee;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendeeRegistrationRepository extends JpaRepository<AttendeeRegistration, UUID> {
    Optional<AttendeeRegistration> findByEventIdAndAttendeeIdAndStatus(UUID eventId, UUID attendeeId, String status);
    long countByEventIdAndStatus(UUID eventId, String status);
}
