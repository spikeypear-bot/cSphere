package com.example.connect_sphere.event.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.event.entity.Event;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
}
