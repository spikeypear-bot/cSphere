package com.example.connect_sphere.venueavailability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;

/** SQL read models share the JPA transaction and connection through JpaTransactionManager. */
@Repository
public class AvailabilityRepository {
    private final JdbcTemplate jdbc;
    public AvailabilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Period> periods(UUID venueId) {
        return jdbc.query("SELECT * FROM venue_unavailability WHERE venue_id=? ORDER BY start_datetime, unavailability_id",
                (r, n) -> new Period(r.getObject("unavailability_id", UUID.class), venueId,
                        r.getObject("start_datetime", OffsetDateTime.class), r.getObject("end_datetime", OffsetDateTime.class),
                        r.getString("reason"), r.getObject("created_at", OffsetDateTime.class)), venueId);
    }

    public Settings settings(UUID venueId) {
        return jdbc.queryForObject("SELECT setup_minutes, turnaround_minutes FROM venues WHERE venue_id=?",
                (r, n) -> new Settings(r.getInt(1), r.getInt(2)), venueId);
    }

    public void settings(UUID venueId, Settings s) {
        jdbc.update("UPDATE venues SET setup_minutes=?, turnaround_minutes=? WHERE venue_id=?",
                s.setupMinutes(), s.turnaroundMinutes(), venueId);
    }

    public List<Booking> bookings(UUID venueId) {
        return bookings(venueId, null, null);
    }

    /** Calendar history includes completed events; operational availability keeps its existing rules. */
    public List<Booking> bookings(UUID venueId, OffsetDateTime start, OffsetDateTime end) {
        boolean calendar = start != null && end != null;
        String filter = calendar
                ? " AND e.status <> 'cancelled' AND e.start_datetime - v.setup_minutes * interval '1 minute' < ? AND e.end_datetime + v.turnaround_minutes * interval '1 minute' > ?"
                : " AND e.status NOT IN ('cancelled','completed')";
        return jdbc.query("""
                SELECT b.booking_id, b.status::text, e.event_id, e.coordinator_id, e.event_name,
                       e.start_datetime, e.end_datetime,
                       e.start_datetime - v.setup_minutes * interval '1 minute' AS effective_start,
                       e.end_datetime + v.turnaround_minutes * interval '1 minute' AS effective_end
                FROM venue_bookings b JOIN events e ON e.event_id=b.event_id JOIN venues v ON v.venue_id=b.venue_id
                WHERE b.venue_id=? AND b.status IN ('pending','approved')
                """ + filter + " ORDER BY e.start_datetime, b.booking_id", (r, n) -> new Booking(r.getObject("booking_id", UUID.class), r.getObject("event_id", UUID.class),
                        r.getObject("coordinator_id", UUID.class), r.getString("event_name"), r.getString("status"),
                        r.getObject("start_datetime", OffsetDateTime.class), r.getObject("end_datetime", OffsetDateTime.class),
                        r.getObject("effective_start", OffsetDateTime.class), r.getObject("effective_end", OffsetDateTime.class)),
                calendar ? new Object[]{venueId, end, start} : new Object[]{venueId});
    }

    public List<Period> periods(UUID venueId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.query("SELECT * FROM venue_unavailability WHERE venue_id=? AND start_datetime < ? AND end_datetime > ? ORDER BY start_datetime, unavailability_id",
                (r, n) -> new Period(r.getObject("unavailability_id", UUID.class), venueId,
                        r.getObject("start_datetime", OffsetDateTime.class), r.getObject("end_datetime", OffsetDateTime.class),
                        r.getString("reason"), r.getObject("created_at", OffsetDateTime.class)), venueId, end, start);
    }

    public void insert(UUID id, UUID venueId, UUID actor, Request r, List<Booking> affected) {
        jdbc.update("INSERT INTO venue_unavailability(unavailability_id,venue_id,start_datetime,end_datetime,reason,created_by) VALUES (?,?,?,?,?,?)",
                id, venueId, r.startDateTime(), r.endDateTime(), r.reason().strip(), actor);
        for (Booking b : affected) {
            jdbc.update("INSERT INTO venue_unavailability_bookings VALUES (?,?)", id, b.bookingId());
            if (b.coordinatorId() != null) {
                jdbc.update("""
                        INSERT INTO notifications(notification_id,recipient_user_id,event_id,type,event_name,reason,message,venue_booking_id)
                        VALUES (?,?,?,'venue_unavailable',?,?,?,?)
                        """, UUID.randomUUID(), b.coordinatorId(), b.eventId(), b.eventName(), r.reason().strip(),
                        "Alternative venue arrangements required. The original booking and event have not been cancelled.", b.bookingId());
            }
        }
    }

    public boolean affected(UUID bookingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM venue_unavailability_bookings i JOIN venue_bookings b USING(booking_id)
                WHERE i.booking_id=? AND b.status IN ('pending','approved'))
                """, Boolean.class, bookingId));
    }
}
