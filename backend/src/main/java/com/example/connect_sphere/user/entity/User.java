package com.example.connect_sphere.user.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An account in the `users` table (V2__init_tables.sql) — no migration was needed
 * for this entity, the table already carries every column it maps.
 *
 * Deliberately a plain domain object: it does <em>not</em> implement Spring
 * Security's {@code UserDetails}. That adaptation lives in {@link
 * com.example.connect_sphere.user.service.UserPrincipal} so the domain model stays
 * independent of the security framework.
 *
 * Flat by design, with {@link UserRole} as a field rather than a subclass per role
 * — per the IS212 Week 5 rule, inheritance needs a real is-a relationship that
 * <em>also</em> needs polymorphism, and no role currently overrides behaviour at a
 * shared call site. See docs/decision-log.md D16.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * BCrypt hash, never a plaintext password. Written only by {@code
     * PasswordEncoder.encode(...)} and compared only by {@code matches(...)}.
     */
    @Column(name = "hashed_password", nullable = false)
    private String hashedPassword;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    /** The login identifier for this project — see AppUserDetailsService. */
    @Column(name = "username", nullable = false, unique = true)
    private String username;

    /**
     * Owned by the database, not by Java — do <em>not</em> set this when creating
     * a user, the value is discarded.
     *
     * {@code @Generated(INSERT)} makes Hibernate leave the column out of the
     * INSERT entirely, which is what lets the column's DEFAULT CURRENT_TIMESTAMP
     * fire; Hibernate then reads the value back so this field is populated after
     * save(). Without it Hibernate names every mapped column in the INSERT and
     * would send an explicit NULL — and an explicit NULL overrides a default, so
     * the NOT NULL constraint rejects the row rather than the default applying.
     *
     * Keeps the schema the single source of truth (Flyway owns it) and uses the
     * database clock, which stays consistent if more than one backend instance
     * ever runs.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private UserRole role;

    /**
     * Free text, nullable — null or "connectSphere" for internal staff. This is
     * the key the RBAC data-scoping rules use to keep an Event Organiser inside
     * their own organisation's requests (AU04).
     */
    @Column(name = "organisation")
    private String organisation;

    public User() {
    }
}
