package com.example.connect_sphere.venueissue.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** VS13: an operational issue reported against a venue. */
@Entity
@Table(name = "venue_operational_issues")
@Getter
@Setter
public class VenueOperationalIssue {

    @Id
    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "affected_from")
    private OffsetDateTime affectedFrom;

    @Column(name = "affected_until")
    private OffsetDateTime affectedUntil;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VenueOperationalIssue() {
    }

    public VenueOperationalIssue(UUID venueId, String description,
            OffsetDateTime affectedFrom, OffsetDateTime affectedUntil, UUID createdBy) {
        this.issueId = UUID.randomUUID();
        this.venueId = venueId;
        this.description = description;
        this.affectedFrom = affectedFrom;
        this.affectedUntil = affectedUntil;
        this.createdBy = createdBy;
    }
}
