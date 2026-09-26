package com.example.connect_sphere.venuebooking.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * EC03's write side of {@code venue_bookings}. {@link VenueBooking} is the
 * read model VS02/VS16 built (immutable, with joined venue/event); this is a
 * separate, flat entity over the same table so creating a booking request
 * doesn't touch the read model's shape. Plain ids rather than associations:
 * writing a booking needs only the keys.
 *
 * <p>The booking's time window is the event's start/end (read through the
 * event), never a copy, so the two can't disagree (Week 1 briefing §8d).
 */
@Entity
@Table(name = "venue_bookings")
@Getter
@Setter
public class VenueBookingRecord {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "venue_id")
    private UUID venueId;

    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "venue_booking_status")
    private VenueBookingStatus status;

    @Column(name = "booking_notes")
    private String bookingNotes;

    @Column(name = "reject_reason")
    private String rejectReason;

    /** V13: why the coordinator requested a venue that lacks a requested
     * accessibility feature (EC03 suitability override). */
    @Column(name = "suitability_note")
    private String suitabilityNote;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    public VenueBookingRecord() {
    }
}
