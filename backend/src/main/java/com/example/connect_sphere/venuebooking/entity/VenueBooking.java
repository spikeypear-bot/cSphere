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

    // EC03 (V13): what the coordinator told Venue Staff when requesting.
    @Column(name = "booking_notes", insertable = false, updatable = false)
    private String bookingNotes;

    @Column(name = "suitability_note", insertable = false, updatable = false)
    private String suitabilityNote;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private OffsetDateTime submittedAt;
}
