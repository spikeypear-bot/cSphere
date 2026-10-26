package com.example.connect_sphere.venuebooking.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;

@Repository
public interface VenueBookingRecordRepository extends JpaRepository<VenueBookingRecord, UUID> {
    List<VenueBookingRecord> findByVenueId(UUID venueId);

    boolean existsByReplacesBookingIdAndStatus(UUID replacesBookingId, VenueBookingStatus status);

    boolean existsByReplacesBookingIdAndStatusIn(UUID replacesBookingId, Collection<VenueBookingStatus> statuses);

    @Query("SELECT b.eventId FROM VenueBookingRecord b WHERE b.bookingId = :id")
    Optional<UUID> findEventId(@Param("id") UUID id);

    interface ApprovalConflict {
        UUID getBookingId();
        String getEventName();
        OffsetDateTime getStartDatetime();
        OffsetDateTime getEndDatetime();
        int getSetupDurationMinutes();
        int getTurnaroundDurationMinutes();
    }

    /** Read after acquiring the venue lock; compare the stored event periods as [start, end). */
    @Query("""
            SELECT b.bookingId AS bookingId, e.eventName AS eventName,
                   COALESCE(b.startDatetime, e.startDatetime) AS startDatetime, COALESCE(b.endDatetime, e.endDatetime) AS endDatetime,
                   b.setupDurationMinutes AS setupDurationMinutes, b.turnaroundDurationMinutes AS turnaroundDurationMinutes
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId AND b.venueId = :venueId
              AND b.bookingId <> :bookingId AND (b.status = :status OR (cast(:status as String) = 'approved' AND cast(b.status as String) = 'attention'))
              AND cast(e.status as String) NOT IN ('cancelled','completed')
              AND COALESCE(b.startDatetime, e.startDatetime) < :end AND COALESCE(b.endDatetime, e.endDatetime) > :start
            ORDER BY COALESCE(b.startDatetime, e.startDatetime), b.bookingId
            """)
    List<ApprovalConflict> findApprovalConflicts(@Param("venueId") UUID venueId, @Param("bookingId") UUID bookingId,
            @Param("status") VenueBookingStatus status, @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    /** Update only status, and clear both entity views of the booking before returning its DTO. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VenueBookingRecord b SET b.status = :approved WHERE b.bookingId = :id AND b.status = :pending")
    int approvePending(@Param("id") UUID id, @Param("pending") VenueBookingStatus pending,
            @Param("approved") VenueBookingStatus approved);

    /** Rejection reason and suggestions change together, only for a still-pending record. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE VenueBookingRecord b
            SET b.status = :rejected, b.rejectReason = :reason,
                b.alternativeVenueId = :alternativeVenueId, b.alternativeArrangement = :alternativeArrangement
            WHERE b.bookingId = :id AND b.status = :pending
            """)
    int rejectPending(@Param("id") UUID id, @Param("pending") VenueBookingStatus pending,
            @Param("rejected") VenueBookingStatus rejected, @Param("reason") String reason,
            @Param("alternativeVenueId") UUID alternativeVenueId,
            @Param("alternativeArrangement") String alternativeArrangement);

    /** EC03: every booking request made for one event, newest first. */
    @Query("SELECT b FROM VenueBookingRecord b WHERE b.eventId = :eventId ORDER BY b.submittedAt DESC NULLS LAST")
    List<VenueBookingRecord> findForEvent(@Param("eventId") UUID eventId);

    // All event workflows use findForEvent; one event can have several active bookings.

    /** An approved booking of any venue that overlaps [start, end), for
     * another event. Half-open on both sides: a booking that ends exactly
     * when this one starts (or starts exactly when it ends) is back-to-back,
     * not an overlap. */
    interface Conflict {
        UUID getVenueId();
        String getEventName();
        OffsetDateTime getStartDatetime();
        OffsetDateTime getEndDatetime();
    }

    interface CoordinatorBooking {
        UUID getVenueId();
        UUID getEventId();
        String getEventName();
        OffsetDateTime getStartDatetime();
        OffsetDateTime getEndDatetime();
        int getSetupDurationMinutes();
        int getTurnaroundDurationMinutes();
    }

    @Query("""
            SELECT b.venueId AS venueId, e.eventId AS eventId, e.eventName AS eventName,
                   COALESCE(b.startDatetime, e.startDatetime) AS startDatetime, COALESCE(b.endDatetime, e.endDatetime) AS endDatetime,
                   b.setupDurationMinutes AS setupDurationMinutes, b.turnaroundDurationMinutes AS turnaroundDurationMinutes
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND e.coordinatorId = :coordinatorId
              AND (b.status = :status OR (cast(:status as String) = 'approved' AND cast(b.status as String) = 'attention'))
            ORDER BY COALESCE(b.startDatetime, e.startDatetime)
            """)
    List<CoordinatorBooking> findApprovedForCoordinator(
            @Param("coordinatorId") UUID coordinatorId,
            @Param("status") VenueBookingStatus status);

    @Query("""
            SELECT b.venueId AS venueId, e.eventName AS eventName,
                   COALESCE(b.startDatetime, e.startDatetime) AS startDatetime, COALESCE(b.endDatetime, e.endDatetime) AS endDatetime
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND (b.status = :status OR (cast(:status as String) = 'approved' AND cast(b.status as String) = 'attention'))
              AND b.eventId <> :eventId
              AND COALESCE(b.startDatetime, e.startDatetime) < :end AND COALESCE(b.endDatetime, e.endDatetime) > :start
            ORDER BY COALESCE(b.startDatetime, e.startDatetime)
            """)
    List<Conflict> findOverlapping(
            @Param("status") VenueBookingStatus status,
            @Param("eventId") UUID eventId,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    @Query("""
            SELECT DISTINCT b.venueId
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND (b.status = :status OR (cast(:status as String) = 'approved' AND cast(b.status as String) = 'attention'))
              AND COALESCE(b.startDatetime, e.startDatetime) < :end AND COALESCE(b.endDatetime, e.endDatetime) > :start
            """)
    List<UUID> findVenueIdsWithOverlappingBookings(
            @Param("status") VenueBookingStatus status,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    /** Bound as a parameter, not a JPQL enum literal: Hibernate renders a
     * literal as a cast to a type named after the Java enum, which does not
     * exist in Postgres (the type is venue_booking_status). */
    default List<Conflict> findApprovedOverlapping(UUID eventId, OffsetDateTime start, OffsetDateTime end) {
        return findOverlapping(VenueBookingStatus.approved, eventId, start, end);
    }
}
