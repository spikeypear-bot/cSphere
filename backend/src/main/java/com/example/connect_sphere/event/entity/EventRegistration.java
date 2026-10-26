package com.example.connect_sphere.event.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** EO10: one attendee's registration. Status: confirmed, withdrawn or event_cancelled. */
@Entity
@Table(name = "event_registrations")
@Getter
@Setter
public class EventRegistration {

    @Id
    @Column(name = "registration_id")
    private UUID registrationId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "attendee_name")
    private String attendeeName;

    private String reference;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    private String status;
}
