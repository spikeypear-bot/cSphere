package com.example.connect_sphere.venuebooking.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@Service
@Transactional
public class BookingPlanService {
    private final JdbcTemplate jdbc;
    public BookingPlanService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Caller holds the event lock. A group may retain its source commitment
     * while one successor is pending, but may never branch around that source. */
    public void requireReplacementEligible(UUID source, UUID candidate) {
        Boolean conflict = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM venue_bookings b
              JOIN venue_bookings s ON s.requirement_id=b.requirement_id
              WHERE s.booking_id=? AND b.booking_id<>s.booking_id
                AND (?::uuid IS NULL OR b.booking_id<>?::uuid)
                AND b.status IN ('pending','approved','attention'))
            """, Boolean.class, source, candidate, candidate);
        if (Boolean.TRUE.equals(conflict))
            throw new VenueBookingStateException("This requirement already has an active booking or replacement. Refresh this event.");
    }
    public void actor(UUID actor) {
        if(actor==null) {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            if(auth!=null && auth.getPrincipal() instanceof Jwt jwt) actor=UUID.fromString(jwt.getSubject());
        }
        jdbc.queryForObject("SELECT set_config('connectsphere.actor_id',?,true)",String.class,actor==null?"":actor.toString());
    }
    public UUID requirement(UUID event,UUID original) {
        if(original!=null) {
            UUID existing=jdbc.queryForObject("SELECT requirement_id FROM venue_bookings WHERE booking_id=?",UUID.class,original);
            if(existing!=null) {
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT withdrawn_at IS NOT NULL FROM venue_booking_requirements WHERE requirement_id=?",Boolean.class,existing))) return existing;
            }
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO venue_booking_requirements(requirement_id,event_id) VALUES (?,?)",id,event);
        if(original!=null) jdbc.update("UPDATE venue_bookings SET requirement_id=? WHERE booking_id=? AND requirement_id IS NULL",id,original);
        return id;
    }
    public long outstanding(UUID event) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM venue_booking_requirements q WHERE q.event_id=? AND q.withdrawn_at IS NULL
            AND NOT EXISTS(SELECT 1 FROM venue_bookings b WHERE b.requirement_id=q.requirement_id AND b.status='approved')
            """,Long.class,event);
    }
    public boolean unresolved(UUID id) {
        return id!=null && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM venue_booking_requirements q WHERE q.requirement_id=? AND q.withdrawn_at IS NULL
                AND NOT EXISTS(SELECT 1 FROM venue_bookings b WHERE b.requirement_id=q.requirement_id AND b.status='approved'))
            """,Boolean.class,id));
    }
    public boolean required(UUID id) {
        return id!=null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT withdrawn_at IS NULL FROM venue_booking_requirements WHERE requirement_id=?",Boolean.class,id));
    }
    public void withdraw(UUID id,UUID actor,String reason) {
        if(id==null) return;
        jdbc.update("UPDATE venue_booking_requirements SET withdrawn_at=clock_timestamp(),withdrawn_by=?,withdrawal_reason=? WHERE requirement_id=? AND withdrawn_at IS NULL",actor,reason,id);
    }
    public java.util.List<java.util.Map<String,Object>> history(UUID booking) {
        return jdbc.query("SELECT history_id,changed_at,actor_id,previous_booking::text,booking::text,event_requirements::text FROM venue_booking_history WHERE booking_id=? ORDER BY history_id",(r,n)-> {
            var result=new LinkedHashMap<String,Object>();
            result.put("historyId",r.getLong("history_id")); result.put("changedAt",r.getObject("changed_at",java.time.OffsetDateTime.class));
            result.put("actorId",r.getObject("actor_id",UUID.class));
            var json=new tools.jackson.databind.ObjectMapper();
            result.put("previousBooking",r.getString("previous_booking")==null?null:json.readTree(r.getString("previous_booking")));
            result.put("booking",json.readTree(r.getString("booking")));
            result.put("eventRequirements",json.readTree(r.getString("event_requirements")));
            return result;
        },booking);
    }
}
