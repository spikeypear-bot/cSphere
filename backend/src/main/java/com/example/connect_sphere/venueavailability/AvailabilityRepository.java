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

    public boolean outsideOperatingHours(UUID venueId, EffectivePeriod period) {
        return jdbc.query("SELECT day_of_week,open_time,close_time FROM venue_operating_hours WHERE venue_id=?",
                (r,n) -> OperatingHours.covers(r.getInt(1), r.getObject(2,java.time.LocalTime.class),
                        r.getObject(3,java.time.LocalTime.class), period), venueId).stream().noneMatch(Boolean::booleanValue);
    }

    public boolean hasOperationalIssue(UUID venueId, EffectivePeriod period) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM venue_operational_issues
                WHERE venue_id=? AND affected_from < ? AND affected_until > ?)
                """, Boolean.class, venueId, period.end(), period.start()));
    }

    public void settings(UUID venueId, Settings s) {
        jdbc.update("UPDATE venues SET setup_minutes=?, turnaround_minutes=? WHERE venue_id=?",
                s.setupMinutes(), s.turnaroundMinutes(), venueId);
    }

    public List<Booking> bookings(UUID venueId) {
        return bookings(venueId, null, null);
    }

    /** One database snapshot prevents a read spanning conversion from missing both
     * the old hold and its newly approved booking. Writers still take venue locks. */
    public List<Booking> blocking(UUID venueId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT b.booking_id, e.event_id, e.coordinator_id, e.event_name, 'approved' AS status,
                    COALESCE(b.start_datetime,e.start_datetime) AS start_datetime,
                    COALESCE(b.end_datetime,e.end_datetime) AS end_datetime,
                    COALESCE(b.start_datetime,e.start_datetime) - b.setup_duration_minutes * interval '1 minute' AS effective_start,
                    COALESCE(b.end_datetime,e.end_datetime) + b.turnaround_duration_minutes * interval '1 minute' AS effective_end
                FROM venue_bookings b JOIN events e USING(event_id)
                WHERE b.venue_id=? AND b.status IN ('approved','attention') AND e.status NOT IN ('cancelled','completed')
                UNION ALL
                SELECT b.booking_id, e.event_id, e.coordinator_id, 'Tentative hold: ' || e.event_name, 'hold',
                    h.reserved_start, h.reserved_end, h.reserved_start, h.reserved_end
                FROM venue_booking_holds h JOIN venue_bookings b USING(booking_id) JOIN events e USING(event_id)
                WHERE b.venue_id=? AND b.status='pending' AND e.status NOT IN ('cancelled','completed')
                    AND h.status='active' AND h.expires_at>?
                """, (r,n) -> new Booking(r.getObject("booking_id",UUID.class),r.getObject("event_id",UUID.class),
                    r.getObject("coordinator_id",UUID.class),r.getString("event_name"),r.getString("status"),
                    r.getObject("start_datetime",OffsetDateTime.class),r.getObject("end_datetime",OffsetDateTime.class),
                    r.getObject("effective_start",OffsetDateTime.class),r.getObject("effective_end",OffsetDateTime.class)),venueId,venueId,now);
    }

    /** Calendar history includes completed events; operational availability keeps its existing rules. */
    public List<Booking> bookings(UUID venueId, OffsetDateTime start, OffsetDateTime end) {
        boolean calendar = start != null && end != null;
        String filter = calendar
                ? " AND e.status <> 'cancelled' AND COALESCE(b.start_datetime,e.start_datetime) - b.setup_duration_minutes * interval '1 minute' < ? AND COALESCE(b.end_datetime,e.end_datetime) + b.turnaround_duration_minutes * interval '1 minute' > ?"
                : " AND e.status NOT IN ('cancelled','completed')";
        return jdbc.query("""
                SELECT b.booking_id, b.status::text, e.event_id, e.coordinator_id, e.event_name,
                       v.venue_address, e.start_datetime AS event_start, e.end_datetime AS event_end,
                       COALESCE(b.start_datetime,e.start_datetime) AS start_datetime, COALESCE(b.end_datetime,e.end_datetime) AS end_datetime,
                       COALESCE(b.start_datetime,e.start_datetime) - b.setup_duration_minutes * interval '1 minute' AS effective_start,
                       COALESCE(b.end_datetime,e.end_datetime) + b.turnaround_duration_minutes * interval '1 minute' AS effective_end
                FROM venue_bookings b JOIN events e ON e.event_id=b.event_id JOIN venues v ON v.venue_id=b.venue_id
                WHERE b.venue_id=? AND b.status IN ('pending','approved','attention')
                """ + filter + " ORDER BY COALESCE(b.start_datetime,e.start_datetime), b.booking_id", (r, n) -> new Booking(r.getObject("booking_id", UUID.class), r.getObject("event_id", UUID.class),
                        r.getObject("coordinator_id", UUID.class), r.getString("event_name"), r.getString("status"),
                        r.getObject("start_datetime", OffsetDateTime.class), r.getObject("end_datetime", OffsetDateTime.class),
                        r.getObject("effective_start", OffsetDateTime.class), r.getObject("effective_end", OffsetDateTime.class),
                        venueId, r.getString("venue_address"), r.getObject("event_start", OffsetDateTime.class),
                        r.getObject("event_end", OffsetDateTime.class)),
                calendar ? new Object[]{venueId, end, start} : new Object[]{venueId});
    }

    public List<Period> periods(UUID venueId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.query("SELECT * FROM venue_unavailability WHERE venue_id=? AND start_datetime < ? AND end_datetime > ? ORDER BY start_datetime, unavailability_id",
                (r, n) -> new Period(r.getObject("unavailability_id", UUID.class), venueId,
                        r.getObject("start_datetime", OffsetDateTime.class), r.getObject("end_datetime", OffsetDateTime.class),
                        r.getString("reason"), r.getObject("created_at", OffsetDateTime.class)), venueId, end, start);
    }

    private static String sgt(OffsetDateTime t) {
        return t.atZoneSameInstant(java.time.ZoneId.of("Asia/Singapore"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public void insert(UUID id, UUID venueId, UUID actor, Request r, List<Booking> affected) {
        jdbc.update("INSERT INTO venue_unavailability(unavailability_id,venue_id,start_datetime,end_datetime,reason,created_by) VALUES (?,?,?,?,?,?)",
                id, venueId, r.startDateTime(), r.endDateTime(), r.reason().strip(), actor);
        String venueAddress = jdbc.queryForObject(
                "SELECT venue_address FROM venues WHERE venue_id=?", String.class, venueId);
        for (Booking b : affected) {
            jdbc.update("INSERT INTO venue_unavailability_bookings VALUES (?,?)", id, b.bookingId());
            if (b.coordinatorId() != null) {
                String message = "Affected booking at " + venueAddress + " ("
                        + sgt(b.startDateTime()) + " to " + sgt(b.endDateTime())
                        + "). Alternative venue arrangements required. The original booking and event have not been cancelled.";
                jdbc.update("""
                        INSERT INTO notifications(notification_id,recipient_user_id,event_id,type,event_name,reason,message,venue_booking_id)
                        VALUES (?,?,?,'venue_unavailable',?,?,?,?)
                        """, UUID.randomUUID(), b.coordinatorId(), b.eventId(), b.eventName(), r.reason().strip(),
                        message, b.bookingId());
            }
        }
    }

    public boolean affected(UUID bookingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM venue_unavailability_bookings i JOIN venue_bookings b USING(booking_id)
                WHERE i.booking_id=? AND b.status IN ('pending','approved','attention'))
                """, Boolean.class, bookingId));
    }
}
