package com.example.connect_sphere.venuemap;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import static com.example.connect_sphere.venuemap.VenueMapDto.*;

@Repository
public class VenueMapRepository {
    private final JdbcTemplate jdbc;
    public VenueMapRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Shared predicate for card counts, date filtering and selected-venue rows.
    // Booking windows take precedence over event windows. No availability decision is made.
    private static final String VISIBLE = """
            WITH visible AS (
                SELECT b.booking_id, b.venue_id, b.event_id, e.event_name, b.status, e.status AS event_status,
                       COALESCE(b.start_datetime,e.start_datetime) AS starts,
                       COALESCE(b.end_datetime,e.end_datetime) AS ends
                FROM venue_bookings b JOIN events e USING(event_id)
                WHERE b.status IN ('approved','pending','attention') AND e.status <> 'cancelled'
                  AND (e.status <> 'completed' OR b.status='approved')
            ), matching AS (
                SELECT * FROM visible
                WHERE ((ends > ? AND event_status <> 'completed')
                       OR (? AND status='approved' AND ends <= ?))
                  AND (?::timestamptz IS NULL OR (starts < ? AND ends > ?))
            )
            """;

    public List<Activity> activity(OffsetDateTime now, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return jdbc.query(VISIBLE + """
                SELECT venue_id, COUNT(DISTINCT event_id) FILTER (WHERE status='approved') AS events,
                       COUNT(*) FILTER (WHERE status='pending') AS pending, COUNT(*) AS matching
                FROM matching GROUP BY venue_id ORDER BY venue_id
                """, (r,n) -> new Activity(r.getObject("venue_id",UUID.class), r.getInt("events"),
                        r.getInt("pending"),r.getInt("matching")),
                now, dayStart != null, now, dayStart, dayEnd, dayStart);
    }

    public List<Booking> bookings(UUID venueId, OffsetDateTime now, OffsetDateTime dayStart,
            OffsetDateTime dayEnd, boolean includePast) {
        return jdbc.query(VISIBLE + """
                SELECT m.*, h.expires_at
                FROM matching m LEFT JOIN venue_booking_holds h
                  ON h.booking_id=m.booking_id AND m.status='pending' AND h.status='active' AND h.expires_at>?
                WHERE m.venue_id=? ORDER BY m.starts, m.booking_id
                """, (r,n) -> new Booking(r.getObject("booking_id",UUID.class),r.getObject("event_id",UUID.class),
                        r.getString("event_name"),VenueBookingStatus.valueOf(r.getString("status")),
                        r.getObject("starts",OffsetDateTime.class),r.getObject("ends",OffsetDateTime.class),
                        r.getObject("expires_at",OffsetDateTime.class),now,
                        !r.getObject("ends",OffsetDateTime.class).isAfter(now)),
                now, includePast || dayStart != null, now, dayStart, dayEnd, dayStart, now, venueId);
    }
}
