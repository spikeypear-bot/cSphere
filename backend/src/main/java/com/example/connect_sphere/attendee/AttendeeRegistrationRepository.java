package com.example.connect_sphere.attendee;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendeeRegistrationRepository extends JpaRepository<AttendeeRegistration, UUID> {
    List<AttendeeRegistration> findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(UUID attendeeId);
    Optional<AttendeeRegistration> findByRegistrationIdAndAttendeeId(UUID registrationId, UUID attendeeId);
    Optional<AttendeeRegistration> findFirstByEventIdAndAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(UUID eventId, UUID attendeeId);
    Optional<AttendeeRegistration> findByEventIdAndAttendeeIdAndStatus(UUID eventId, UUID attendeeId, String status);
    long countByEventIdAndStatus(UUID eventId, String status);
}
