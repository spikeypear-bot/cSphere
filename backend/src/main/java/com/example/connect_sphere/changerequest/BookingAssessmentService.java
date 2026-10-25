package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.connect_sphere.event.entity.*;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventLifecycleService;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.*;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.*;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.service.*;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/** Shared VS17 reassessment. Event locks precede venue locks; no reservation periods are written. */
@Service
@Transactional(readOnly=true)
public class BookingAssessmentService {
    public record History(UUID assessmentId, String outcome, String source, String reason, String actorName, OffsetDateTime createdAt) {}
    public record View(UUID bookingId, String status, List<ChangeRequestDto.ImpactItem> checks, List<History> history, boolean canReassess) {}
    private final VenueBookingRecordRepository bookings;
    private final EventRepository events;
    private final VenueRepository venues;
    private final ChangeImpactService impact;
    private final EventLifecycleService lifecycle;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    public BookingAssessmentService(VenueBookingRecordRepository bookings, EventRepository events, VenueRepository venues,
            ChangeImpactService impact, EventLifecycleService lifecycle, JdbcTemplate jdbc, UserRepository users,
            org.springframework.context.ApplicationEventPublisher publisher) {
        this.bookings=bookings; this.events=events; this.venues=venues; this.impact=impact;
        this.lifecycle=lifecycle; this.jdbc=jdbc; this.users=users; this.publisher=publisher;
    }
    @Transactional
    public Set<UUID> lockVenueEvents(UUID venueId) {
        // Catalogue edits and booking submissions share the venue lock. Existing events are locked first.
        var ids=new TreeSet<UUID>();
        bookings.findByVenueId(venueId).forEach(b -> ids.add(b.getEventId()));
        ids.forEach(id -> events.findForUpdate(id));
        return ids;
    }
    public String venueSnapshot(UUID venueId) {
        return jdbc.queryForObject("""
            SELECT (to_jsonb(v) || jsonb_build_object('operating_hours',
                (SELECT coalesce(jsonb_agg(jsonb_build_object('day',h.day_of_week,'open',h.open_time,'close',h.close_time) ORDER BY day_of_week,open_time),'[]'::jsonb)
                 FROM venue_operating_hours h WHERE h.venue_id=v.venue_id)))::text
            FROM venues v WHERE venue_id=?
            """,String.class,venueId);
    }
    @Transactional
    public void venueChanged(UUID venueId, UUID actor, boolean layoutChanged, String previousVenue, Set<UUID> lockedEvents) {
        bookings.flush();
        if (bookings.findByVenueId(venueId).stream().anyMatch(b -> !lockedEvents.contains(b.getEventId())))
            throw new VenueBookingStateException("A new booking arrived while editing this venue. Retry saving the venue details.");
        for (var b:bookings.findByVenueId(venueId)) reassess(b,actor,"Venue details changed",layoutChanged,previousVenue);
    }
    @Transactional
    public void requirementsChanged(Event event, UUID actor) {
        bookings.findForEvent(event.getEventId()).stream().map(VenueBookingRecord::getVenueId).distinct().sorted()
            .forEach(id -> venues.findForUpdate(id));
        bookings.flush();
        for (var b:bookings.findForEvent(event.getEventId())) reassess(b,actor,"Approved event requirements changed",false,null);
    }
    private void reassess(VenueBookingRecord b, UUID actor, String source, boolean layoutChanged, String previousVenue) {
        var e=events.findById(b.getEventId()).orElseThrow();
        if (!active(b,e)) return;
        var failed=impact.assessBooking(e,b).stream().filter(c -> "attention".equals(c.status())).map(ChangeRequestDto.ImpactItem::detail).toList();
        String reason=String.join(" ",failed);
        if (layoutChanged && e.getVenueRequirements()!=null && !e.getVenueRequirements().isBlank())
            reason += " Supported layouts changed. Staff must reassess the written venue requirements.";
        if (reason.isBlank()) return; // Recovery requires an explicit staff decision.
        var history=history(b.getBookingId());
        if (b.getStatus()!=VenueBookingStatus.approved && b.getStatus()!=VenueBookingStatus.attention) return;
        if (b.getStatus()==VenueBookingStatus.attention && !history.isEmpty() && history.getFirst().reason().equals(reason)
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT venue_snapshot = ?::jsonb FROM venue_booking_assessments WHERE assessment_id=?",Boolean.class,venueSnapshot(b.getVenueId()),history.getFirst().assessmentId()))) return;
        b.setStatus(VenueBookingStatus.attention); bookings.saveAndFlush(b);
        record(b,e,actor,"attention",source,reason,previousVenue);
        lifecycle.returnToPlanning(e.getEventId(),actor,reason);
        events.flush();
        notify(e,b,reason);
    }
    public View view(UUID bookingId) {
        var b=booking(bookingId); var e=events.findById(b.getEventId()).orElseThrow();
        return new View(bookingId,b.getStatus().name(),impact.assessBooking(e,b),history(bookingId),active(b,e) && b.getStatus()==VenueBookingStatus.attention);
    }
    public List<View> forCoordinator(UUID eventId, UUID actor) {
        var e=events.findById(eventId).orElseThrow();
        if (!actor.equals(e.getCoordinatorId())) throw new com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException(eventId);
        return bookings.findForEvent(eventId).stream().filter(b -> b.getStatus()==VenueBookingStatus.attention || !history(b.getBookingId()).isEmpty())
            .map(b -> view(b.getBookingId())).toList();
    }
    @Transactional
    public View restore(UUID bookingId, UUID actor, UUID expectedAssessmentId, String note) {
        var eventId=bookings.findEventId(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var e=events.findForUpdate(eventId).orElseThrow(); var b=booking(bookingId);
        venues.findForUpdate(b.getVenueId()).orElseThrow();
        if (!active(b,e) || b.getStatus()!=VenueBookingStatus.attention)
            throw new VenueBookingStateException("This booking no longer requires reassessment. Refresh its details.");
        if (bookings.existsByReplacesBookingIdAndStatusIn(bookingId,List.of(VenueBookingStatus.pending,VenueBookingStatus.approved,VenueBookingStatus.attention)))
            throw new VenueBookingStateException("Resolve the replacement request before restoring this booking approval.");
        var h=history(bookingId);
        if (h.isEmpty() || !h.getFirst().assessmentId().equals(expectedAssessmentId))
            throw new VenueBookingStateException("The booking assessment changed. Refresh before reassessing.");
        if (note==null || note.isBlank() || note.strip().length()>2000)
            throw new InvalidVenueBookingException("Explain the reassessment in 1 to 2000 characters.");
        if (impact.assessBooking(e,b).stream().anyMatch(c -> "attention".equals(c.status())))
            throw new VenueBookingStateException("Current venue checks still require attention. Resolve the issues before restoring approval.");
        b.setStatus(VenueBookingStatus.approved); bookings.saveAndFlush(b);
        record(b,e,actor,"restored","Staff reassessment",note.strip(),null);
        notify(e,b,"Venue Staff restored booking approval. The event must complete its remaining planning and safety checks.");
        return view(bookingId);
    }
    private List<History> history(UUID id) {
        return jdbc.query("SELECT * FROM venue_booking_assessments WHERE booking_id=? ORDER BY created_at DESC, assessment_id DESC",
            (rs,n) -> new History(rs.getObject("assessment_id",UUID.class),rs.getString("outcome"),rs.getString("source"),
                rs.getString("reason"),rs.getString("actor_name"),rs.getObject("created_at",OffsetDateTime.class)),id);
    }
    private void record(VenueBookingRecord b, Event e, UUID actor, String outcome, String source, String reason, String previousVenue) {
        String name=actor==null ? null : users.findById(actor).map(u -> u.getUsername()).orElse(null);
        jdbc.update("""
            INSERT INTO venue_booking_assessments(assessment_id,booking_id,outcome,source,reason,actor_id,actor_name,created_at,previous_venue_snapshot,venue_snapshot,requirements_snapshot)
            SELECT ?,?,?,?,?,?,?,clock_timestamp(), ?::jsonb, ?::jsonb, jsonb_build_object('attendance',e.expected_attendance,
             'venueRequirements',e.venue_requirements,'accessibility',e.accessibility_needs,'facilities',e.required_facilities,
             'start',e.start_datetime,'end',e.end_datetime)
            FROM venues v, events e WHERE v.venue_id=? AND e.event_id=?
            """,UUID.randomUUID(),b.getBookingId(),outcome,source,reason,actor,name,previousVenue,venueSnapshot(b.getVenueId()),b.getVenueId(),e.getEventId());
        // Staff comments remain in the assignment-scoped assessment history, not the organiser timeline.
        String auditMessage=source + ": " + ("restored".equals(outcome) ? "Booking approval restored after staff reassessment." : reason);
        publisher.publishEvent(new EventChange(e.getEventId(),ActivityType.venue_suitability_changed,actor,
            auditMessage.substring(0,Math.min(2000,auditMessage.length()))));
    }
    private void notify(Event e, VenueBookingRecord b, String message) {
        if (e.getCoordinatorId()!=null) notification(e.getCoordinatorId(),e,b,"venue_suitability_changed",message);
        if (e.getFirstConfirmedAt()!=null)
            users.findByRoleAndOrganisation(UserRole.eo,e.getOrganisation()).forEach(u -> notification(u.getUserId(),e,b,"venue_arrangements_changed",message));
    }
    private void notification(UUID recipient, Event e, VenueBookingRecord b, String type, String message) {
        jdbc.update("""
            INSERT INTO notifications(notification_id,recipient_user_id,event_id,venue_booking_id,type,event_name,message)
            VALUES (?,?,?,?,?::notification_type,?,?)
            """,UUID.randomUUID(),recipient,e.getEventId(),b.getBookingId(),type,e.getEventName(),message);
    }
    private VenueBookingRecord booking(UUID id) { return bookings.findById(id).orElseThrow(() -> new VenueBookingNotFoundException(id)); }
    private boolean active(VenueBookingRecord b, Event e) {
        return Set.of(VenueBookingStatus.pending,VenueBookingStatus.approved,VenueBookingStatus.attention).contains(b.getStatus())
          && e.getStatus()!=EventStatus.cancelled && e.getStatus()!=EventStatus.completed && e.getEndDatetime().isAfter(OffsetDateTime.now());
    }
}
