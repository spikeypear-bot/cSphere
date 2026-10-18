package com.example.connect_sphere.venuebooking.hold;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.connect_sphere.venuebooking.service.VenueBookingStateException;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Booking;

@Repository
public class VenueHoldRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public VenueHoldRepository(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private static final String SELECT = """
        SELECT h.*, b.venue_id, b.event_id, e.event_name, b.status::text AS booking_status
        FROM venue_booking_holds h JOIN venue_bookings b USING(booking_id) JOIN events e USING(event_id)
        """;
    public List<VenueHold> history(UUID bookingId) {
        var now = now();
        return jdbc.query(SELECT + " WHERE booking_id=? ORDER BY h.created_at DESC,h.hold_id", (r,n) -> {
            String status = r.getString("status");
            var expiry = r.getObject("expires_at", OffsetDateTime.class);
            if (status.equals("active") && !expiry.isAfter(now)) status = "expired";
            if (status.equals("active") && !r.getString("booking_status").equals("pending")) status = "released";
            return new VenueHold(r.getObject("hold_id", UUID.class), bookingId, r.getObject("venue_id", UUID.class),
                r.getObject("event_id", UUID.class), r.getString("event_name"),
                r.getObject("reserved_start", OffsetDateTime.class), r.getObject("reserved_end", OffsetDateTime.class),
                expiry, status, r.getObject("created_by", UUID.class), r.getObject("created_at", OffsetDateTime.class),
                r.getObject("ended_at", OffsetDateTime.class), now);
        }, bookingId);
    }
    public List<Booking> blocking(UUID venueId) {
        return jdbc.query("""
            SELECT h.*, b.event_id, e.coordinator_id, e.event_name
            FROM venue_booking_holds h JOIN venue_bookings b USING(booking_id) JOIN events e USING(event_id)
            WHERE b.venue_id=? AND b.status='pending' AND e.status NOT IN ('cancelled','completed')
            AND h.status='active' AND h.expires_at>?
            """, (r,n) -> new Booking(r.getObject("booking_id",UUID.class), r.getObject("event_id",UUID.class),
                r.getObject("coordinator_id",UUID.class), "Tentative hold: " + r.getString("event_name"), "hold",
                r.getObject("reserved_start",OffsetDateTime.class),r.getObject("reserved_end",OffsetDateTime.class),
                r.getObject("reserved_start",OffsetDateTime.class),r.getObject("reserved_end",OffsetDateTime.class)), venueId,now());
    }
    public UUID bookingId(UUID holdId) {
        return jdbc.query("SELECT booking_id FROM venue_booking_holds WHERE hold_id=?", (r,n)->r.getObject(1,UUID.class),holdId)
            .stream().findFirst().orElseThrow(()->new com.example.connect_sphere.venuebooking.service.VenueBookingNotFoundException(holdId));
    }
    public void insert(UUID id, UUID bookingId, OffsetDateTime start, OffsetDateTime end, OffsetDateTime expiry, UUID actor) {
        jdbc.update("INSERT INTO venue_booking_holds(hold_id,booking_id,reserved_start,reserved_end,expires_at,status,created_by,created_at) VALUES (?,?,?,?,?,'active',?,?)",
            id,bookingId,start,end,expiry,actor,now());
    }
    public void requireActive(UUID holdId, UUID bookingId) {
        if (history(bookingId).stream().noneMatch(h -> h.holdId().equals(holdId) && h.status().equals("active")))
            throw new VenueBookingStateException("This tentative hold is no longer active. Refresh its details.");
    }
    public boolean hasActive(UUID bookingId) { return history(bookingId).stream().anyMatch(h -> h.status().equals("active")); }
    public void transition(UUID holdId, String status, OffsetDateTime endedAt) {
        String deadline = status.equals("expired") ? "" : " AND expires_at > ?";
        Object[] parameters = status.equals("expired") ? new Object[]{status,endedAt,holdId}
                : new Object[]{status,endedAt,holdId,endedAt};
        if (jdbc.update("UPDATE venue_booking_holds SET status=?,ended_at=? WHERE hold_id=? AND status='active'" + deadline,parameters)!=1)
            throw new VenueBookingStateException("The hold changed. Refresh its details.");
    }
    public List<UUID> overdue() {
        return jdbc.query("SELECT hold_id FROM venue_booking_holds WHERE status='active' AND expires_at<=? ORDER BY expires_at LIMIT 100",
            (r,n)->r.getObject(1,UUID.class),now());
    }
}
