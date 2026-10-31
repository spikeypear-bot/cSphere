package com.example.connect_sphere.attendee;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AttendeeRegistrationRepository extends JpaRepository<AttendeeRegistration, UUID> {
    // Scalar lookup avoids caching a stale registration before waiting for the event lock.
    @Query("SELECT r.eventId FROM AttendeeRegistration r WHERE r.registrationId = :id AND r.attendeeId = :owner")
    Optional<UUID> findOwnedEventId(@Param("id") UUID registrationId, @Param("owner") UUID attendeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AttendeeRegistration r WHERE r.registrationId = :id AND r.attendeeId = :owner")
    Optional<AttendeeRegistration> findOwnedForUpdate(@Param("id") UUID registrationId, @Param("owner") UUID attendeeId);

    List<AttendeeRegistration> findByAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(UUID attendeeId);
    List<AttendeeRegistration> findByEventIdAndStatusAndAttendeeIdIsNotNull(UUID eventId, String status);
    Optional<AttendeeRegistration> findByRegistrationIdAndAttendeeId(UUID registrationId, UUID attendeeId);
    Optional<AttendeeRegistration> findFirstByEventIdAndAttendeeIdOrderByRegisteredAtDescRegistrationIdAsc(UUID eventId, UUID attendeeId);
    Optional<AttendeeRegistration> findByEventIdAndAttendeeIdAndStatus(UUID eventId, UUID attendeeId, String status);
    long countByEventIdAndStatus(UUID eventId, String status);
}
