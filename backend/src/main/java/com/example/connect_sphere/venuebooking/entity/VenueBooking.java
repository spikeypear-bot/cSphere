package com.example.connect_sphere.venuebooking.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import com.example.connect_sphere.venue.entity.Venue;

/** Read-only booking relationship, with no cascading writes. */
@Entity
@Table(name = "venue_bookings")
@Immutable
@Getter
public class VenueBooking {
    @Id
    @Column(name = "booking_id")
    private UUID bookingId;
    @Column(name = "replaces_booking_id")
    private UUID replacesBookingId;
    @Column(name = "setup_duration_minutes", nullable = false)
    private int setupDurationMinutes;
    @Column(name = "turnaround_duration_minutes", nullable = false)
    private int turnaroundDurationMinutes;
    @Column(name = "start_datetime")
    private OffsetDateTime startDatetime;
    @Column(name = "end_datetime")
    private OffsetDateTime endDatetime;
    @Column(name = "timing_note")
    private String timingNote;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "venue_booking_status")
    private VenueBookingStatus status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", insertable = false, updatable = false)
    private Venue venue;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private VenueEvent event;

    @Column(name = "reject_reason", insertable = false, updatable = false)
    private String rejectReason;

    @Column(name = "alternative_venue_id", insertable = false, updatable = false)
    private UUID alternativeVenueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alternative_venue_id", insertable = false, updatable = false)
    private Venue alternativeVenue;

    @Column(name = "alternative_arrangement", insertable = false, updatable = false)
    private String alternativeArrangement;

    // EC03 (V13): what the coordinator told Venue Staff when requesting.
    @Column(name = "booking_notes", insertable = false, updatable = false)
    private String bookingNotes;

    @Column(name = "suitability_note", insertable = false, updatable = false)
    private String suitabilityNote;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private OffsetDateTime submittedAt;
}
