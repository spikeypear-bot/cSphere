package com.example.connect_sphere.safety;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Each venue's attestation is valid only for its current booking, requirements and catalogue. */
@Service
public class VenueSafetyChecklist {
    public record Item(String key,String label,String fingerprint) {}
    private final JdbcTemplate jdbc;
    public VenueSafetyChecklist(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockVenues(UUID event) {
        jdbc.query("SELECT DISTINCT venue_id FROM venue_bookings WHERE event_id=? AND status IN ('pending','approved','attention')",
            (r,n)->r.getObject(1,UUID.class),event).stream().sorted().forEach(id ->
                jdbc.queryForObject("SELECT venue_id FROM venues WHERE venue_id=? FOR UPDATE",UUID.class,id));
    }
    public List<Item> items(UUID event) {
        return jdbc.query("""
            SELECT b.booking_id,v.venue_address,
              md5((to_jsonb(b) || jsonb_build_object('venue',to_jsonb(v)-'setup_minutes'-'turnaround_minutes',
                'hours',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.day_of_week,h.open_time),'[]'::jsonb)
                  FROM venue_operating_hours h WHERE h.venue_id=v.venue_id),
                'eventRequirements',jsonb_build_object('attendance',e.expected_attendance,'facilities',e.required_facilities,
                  'accessibility',e.accessibility_needs,'written',e.venue_requirements,
                  'start',e.start_datetime,'end',e.end_datetime)))::text) AS fingerprint
            FROM venue_bookings b JOIN events e USING(event_id) JOIN venues v ON v.venue_id=b.venue_id
            WHERE b.event_id=? AND b.status IN ('pending','approved','attention')
            ORDER BY v.venue_address,b.booking_id
            """,(r,n)->new Item("venue_safety_"+r.getObject("booking_id"),
                "Safety, capacity, facilities and accessibility verified: "+r.getString("venue_address")+
                " (booking "+r.getObject("booking_id")+")",r.getString("fingerprint")),event);
    }
    public boolean complete(UUID event) {
        return items(event).stream().allMatch(i -> Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM event_readiness_items WHERE event_id=? AND item_key=?
                AND checked AND arrangement_fingerprint=?)
            """,Boolean.class,event,i.key(),i.fingerprint())));
    }
}
