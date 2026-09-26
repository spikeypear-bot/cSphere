package com.example.connect_sphere.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Username is this project's login identifier, so this is the lookup
     * {@code UserDetailsService} runs on every authentication attempt. The column
     * is UNIQUE in V2__init_tables.sql, which is what makes returning a single
     * Optional safe rather than a List.
     */
    Optional<User> findByUsername(String username);

    /** EC01: every Event Organiser of the organisation that owns a request. */
    List<User> findByRoleAndOrganisation(UserRole role, String organisation);

    /** EC03: every Venue Staff member (no staff-to-venue ownership exists). */
    List<User> findByRole(UserRole role);

    /**
     * Lets the dev seeder stay idempotent — it runs on every boot, and both
     * `username` and `email` are UNIQUE, so a blind insert would fail on the
     * second startup rather than quietly doing nothing.
     */
    boolean existsByUsername(String username);
}
