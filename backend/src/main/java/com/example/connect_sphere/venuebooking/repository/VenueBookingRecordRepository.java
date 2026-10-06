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

    @Query("SELECT b.eventId FROM VenueBookingRecord b WHERE b.bookingId = :id")
    Optional<UUID> findEventId(@Param("id") UUID id);

    @Query("""
            SELECT count(b) FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId AND b.venueId = :venueId
              AND b.bookingId <> :bookingId AND b.status = :status
              AND e.startDatetime < :end AND e.endDatetime > :start
            """)
    long countApprovalConflicts(@Param("venueId") UUID venueId, @Param("bookingId") UUID bookingId,
            @Param("status") VenueBookingStatus status, @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    /** Update only status, and clear both entity views of the booking before returning its DTO. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VenueBookingRecord b SET b.status = :approved WHERE b.bookingId = :id AND b.status = :pending")
    int approvePending(@Param("id") UUID id, @Param("pending") VenueBookingStatus pending,
            @Param("approved") VenueBookingStatus approved);

    /** Both rejection fields change together, only for a still-pending record. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VenueBookingRecord b SET b.status = :rejected, b.rejectReason = :reason WHERE b.bookingId = :id AND b.status = :pending")
    int rejectPending(@Param("id") UUID id, @Param("pending") VenueBookingStatus pending,
            @Param("rejected") VenueBookingStatus rejected, @Param("reason") String reason);

    /** EC03: every booking request made for one event, newest first. */
    @Query("SELECT b FROM VenueBookingRecord b WHERE b.eventId = :eventId ORDER BY b.submittedAt DESC NULLS LAST")
    List<VenueBookingRecord> findForEvent(@Param("eventId") UUID eventId);

    /** EC03 "one active booking request per event". */
    Optional<VenueBookingRecord> findFirstByEventIdAndStatusIn(UUID eventId, Collection<VenueBookingStatus> statuses);

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
    }

    @Query("""
            SELECT b.venueId AS venueId, e.eventId AS eventId, e.eventName AS eventName,
                   e.startDatetime AS startDatetime, e.endDatetime AS endDatetime
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND e.coordinatorId = :coordinatorId
              AND b.status = :status
            ORDER BY e.startDatetime
            """)
    List<CoordinatorBooking> findApprovedForCoordinator(
            @Param("coordinatorId") UUID coordinatorId,
            @Param("status") VenueBookingStatus status);

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

    @Query("""
            SELECT DISTINCT b.venueId
            FROM VenueBookingRecord b, Event e
            WHERE e.eventId = b.eventId
              AND b.status = :status
              AND e.startDatetime < :end AND e.endDatetime > :start
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
