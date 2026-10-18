package com.example.connect_sphere.venueavailability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Booking;

/** Detects existing overlaps without changing any booking or event. */
@Service
public class TimingConflictService {
    private final AvailabilityRepository availability;
    private final VenueRepository venues;
    private final JdbcTemplate jdbc;
    public TimingConflictService(AvailabilityRepository availability, VenueRepository venues, JdbcTemplate jdbc) {
        this.availability = availability; this.venues = venues; this.jdbc = jdbc;
    }
    public record Overlap(Booking first, Booking second) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public java.time.OffsetDateTime overlapStart() {
            return first.effectiveStart().isAfter(second.effectiveStart()) ? first.effectiveStart() : second.effectiveStart();
        }
        @com.fasterxml.jackson.annotation.JsonProperty
        public java.time.OffsetDateTime overlapEnd() {
            return first.effectiveEnd().isBefore(second.effectiveEnd()) ? first.effectiveEnd() : second.effectiveEnd();
        }
    }

    @Transactional(readOnly = true)
    public List<Overlap> list(UUID venueId) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return overlaps(availability.bookings(venueId));
    }

    /** Reused by schedule reads so off-screen partners still mark visible bookings. */
    static List<Overlap> overlaps(List<Booking> bookings) {
        var approved = bookings.stream().filter(b -> b.status().equals("approved"))
                .sorted(java.util.Comparator.comparing(b -> b.bookingId().toString())).toList();
        List<Overlap> result = new ArrayList<>();
        for (int i = 0; i < approved.size(); i++) {
            for (int j = i + 1; j < approved.size(); j++) {
                var a = approved.get(i); var b = approved.get(j);
                if (AvailabilityService.overlaps(a.effectiveStart(), a.effectiveEnd(), b.effectiveStart(), b.effectiveEnd()))
                    result.add(new Overlap(a, b));
            }
        }
        return result;
    }

    @Transactional
    public List<Overlap> scan(UUID venueId) {
        venues.findForUpdate(venueId).orElseThrow(() -> new VenueNotFoundException(venueId));
        var current = list(venueId);
        var keys = new HashSet<String>();
        var notify = new java.util.LinkedHashMap<UUID, Booking>();
        for (var pair : current) {
            var a = pair.first(); var b = pair.second();
            keys.add(a.bookingId() + ":" + b.bookingId());
            String fingerprint = a.effectiveStart().toInstant() + "|" + a.effectiveEnd().toInstant() + "|"
                    + b.effectiveStart().toInstant() + "|" + b.effectiveEnd().toInstant() + "|" + a.coordinatorId() + "|" + b.coordinatorId();
            var changed = jdbc.query("""
                    INSERT INTO venue_timing_conflicts(first_booking_id,second_booking_id,fingerprint) VALUES (?,?,?)
                    ON CONFLICT(first_booking_id,second_booking_id) DO UPDATE SET fingerprint=EXCLUDED.fingerprint
                    WHERE venue_timing_conflicts.fingerprint<>EXCLUDED.fingerprint RETURNING first_booking_id
                    """, (r,n) -> r.getObject(1, UUID.class), a.bookingId(), b.bookingId(), fingerprint);
            if (!changed.isEmpty()) { notify.put(a.bookingId(), a); notify.put(b.bookingId(), b); }
        }
        var saved = jdbc.query("""
                SELECT c.first_booking_id,c.second_booking_id FROM venue_timing_conflicts c
                JOIN venue_bookings b ON b.booking_id=c.first_booking_id WHERE b.venue_id=?
                """, (r,n) -> new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)}, venueId);
        for (var pair : saved) {
            if (!keys.contains(pair[0] + ":" + pair[1])) jdbc.update(
                    "DELETE FROM venue_timing_conflicts WHERE first_booking_id=? AND second_booking_id=?", pair[0], pair[1]);
        }
        for (var b : notify.values()) notifyCoordinator(b.coordinatorId(), b.eventId(), b.bookingId(), b.eventName(),
                "venue_timing_conflict", "This approved venue booking overlaps another approved booking including setup/turnaround. Review occupied times with Venue Staff. No booking has been cancelled.");
        return current;
    }

    /** Part of the caller's transaction: a failed notification also rolls back the timing edit/scan. */
    public void notifyCoordinator(UUID coordinatorId, UUID eventId, UUID bookingId, String eventName, String type, String message) {
        if (coordinatorId == null) return;
        jdbc.update("""
                INSERT INTO notifications(notification_id,recipient_user_id,event_id,venue_booking_id,type,event_name,message)
                VALUES (?,?,?,?,?::notification_type,?,?)
                """, UUID.randomUUID(), coordinatorId, eventId, bookingId, type, eventName, message);
    }
}
