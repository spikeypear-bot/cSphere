package com.example.connect_sphere.venue.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.venue.entity.Venue;

@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {
}
