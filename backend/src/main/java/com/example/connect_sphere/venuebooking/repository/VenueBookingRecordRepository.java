package com.example.connect_sphere.venuebooking.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

@Repository
public interface VenueBookingRecordRepository extends JpaRepository<VenueBookingRecord, UUID> {

    /** EC03: every booking request made for one event, newest first. */
    @Query("SELECT b FROM VenueBookingRecord b WHERE b.eventId = :eventId ORDER BY b.submittedAt DESC NULLS LAST")
    List<VenueBookingRecord> findForEvent(@Param("eventId") UUID eventId);

    /** EC03 "one active booking request per event". */
    Optional<VenueBookingRecord> findFirstByEventIdAndStatusIn(UUID eventId, Collection<VenueBookingStatus> statuses);

    /** A confirmed booking of any venue that overlaps [start, end), for
     * another event. Half-open on both sides: a booking that ends exactly
     * when this one starts (or starts exactly when it ends) is back-to-back,
     * not an overlap. */
    interface Conflict {
        UUID getVenueId();
        String getEventName();
        OffsetDateTime getStartDatetime();
        OffsetDateTime getEndDatetime();
    }

    @Query("""
            SELECT b.venueId AS venueId, e.eventName AS eventName,
                   e.startDatetime AS startDatetime, e.endDatetime AS endDatetime
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND b.status = :status
              AND b.eventId <> :eventId
              AND e.startDatetime < :end AND e.endDatetime > :start
            ORDER BY e.startDatetime
            """)
    List<Conflict> findOverlapping(
            @Param("status") VenueBookingStatus status,
            @Param("eventId") UUID eventId,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    /** Bound as a parameter, not a JPQL enum literal: Hibernate renders a
     * literal as a cast to a type named after the Java enum, which does not
     * exist in Postgres (the type is venue_booking_status). */
    default List<Conflict> findConfirmedOverlapping(UUID eventId, OffsetDateTime start, OffsetDateTime end) {
        return findOverlapping(VenueBookingStatus.confirmed, eventId, start, end);
    }
}
