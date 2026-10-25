package com.example.connect_sphere.event.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.connect_sphere.event.entity.EventRegistration;

public interface EventRegistrationRepository extends JpaRepository<EventRegistration, UUID> {

    List<EventRegistration> findByEventIdOrderByRegisteredAtAsc(UUID eventId);
}
