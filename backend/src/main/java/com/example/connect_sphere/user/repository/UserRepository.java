package com.example.connect_sphere.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.user.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Username is this project's login identifier, so this is the lookup
     * {@code UserDetailsService} runs on every authentication attempt. The column
     * is UNIQUE in V2__init_tables.sql, which is what makes returning a single
     * Optional safe rather than a List.
     */
    Optional<User> findByUsername(String username);
}
