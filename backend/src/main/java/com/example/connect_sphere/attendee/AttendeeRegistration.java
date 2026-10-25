package com.example.connect_sphere.attendee;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "event_registrations")
@Getter
@Setter
public class AttendeeRegistration {
    @Id
    @Column(name = "registration_id")
    private UUID registrationId;
    @Column(name = "registration_reference")
    private String registrationReference;
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "attendee_id")
    private UUID attendeeId;
    @Column(name = "attendee_name")
    private String attendeeName;
    @Column(name = "attendee_email")
    private String attendeeEmail;
    private String status;
    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;
}
