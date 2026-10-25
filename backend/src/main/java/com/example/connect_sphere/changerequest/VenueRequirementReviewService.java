package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.*;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.service.*;

@Service
@Transactional(readOnly = true)
public class VenueRequirementReviewService {
    public record View(VenueRequirementReview review, List<ChangeRequestDto.FieldChange> changes,
            List<ChangeRequestDto.ImpactItem> checks, OffsetDateTime reservedStart, OffsetDateTime reservedEnd,
            int setupDurationMinutes, int turnaroundDurationMinutes, boolean canReview, ChangeImpactService.BookingSummary suitability, String bookingStatus, boolean outcomeCurrent) {}
    public record Decision(String outcome, String note) {}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final VenueRequirementReviewRepository reviews;
    private final VenueBookingRecordRepository bookings;
    private final EventRepository events;
    private final VenueRepository venues;
    private final ChangeImpactService impact;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final EventRequestRepository requests;

    public VenueRequirementReviewService(VenueRequirementReviewRepository reviews, VenueBookingRecordRepository bookings,
            EventRepository events, VenueRepository venues, ChangeImpactService impact, UserRepository users,
            NotificationService notifications, ActivityService activity, EventRequestRepository requests) {
        this.reviews=reviews; this.bookings=bookings; this.events=events; this.venues=venues; this.impact=impact;
        this.users=users; this.notifications=notifications; this.activity=activity; this.requests=requests;
    }
    public List<View> forBooking(UUID bookingId) {
        var booking=booking(bookingId); var event=event(booking.getEventId());
        return reviews.findByBookingIdOrderByApprovedAtDescReviewIdAsc(bookingId).stream().map(r -> view(r,booking,event,true)).toList();
    }
    public List<View> forCoordinator(UUID eventId, UUID actor) {
        var event=event(eventId);
        if (!actor.equals(event.getCoordinatorId())) throw new NotAssignedCoordinatorException(eventId);
        return reviews.findByEventIdOrderByApprovedAtDescReviewIdAsc(eventId).stream()
                .map(r -> view(r,booking(r.getBookingId()),event,false)).toList();
    }
    @Transactional
    public View decide(UUID bookingId, UUID reviewId, UUID actor, Decision decision) {
        UUID eventId=bookings.findEventId(bookingId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        // Same lock order as booking decisions and approved event changes.
        var event=events.findForUpdate(eventId).orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        var booking=booking(bookingId);
        venues.findForUpdate(booking.getVenueId()).orElseThrow();
        var review=reviews.findForUpdate(reviewId).filter(r -> r.getBookingId().equals(bookingId))
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId));
        if (!"pending".equals(review.getStatus())) throw new VenueBookingStateException("This requirement review has already been recorded. Refresh to see the outcome.");
        if (booking.getStatus()==VenueBookingStatus.attention) throw new VenueBookingStateException("Resolve the booking assessment before recording this requirement review.");
        if (!active(booking,event)) throw new VenueBookingStateException("Only active pending or approved bookings can be reviewed. Refresh the booking details.");
        if (decision == null || !Set.of("suitable","action_required").contains(Objects.toString(decision.outcome(),"")))
            throw new InvalidVenueBookingException("Choose remains suitable or coordinator action required.");
        String note=decision.note()==null ? null : decision.note().strip();
        if (note != null && note.isBlank()) note=null;
        if (note != null && note.length()>2000) throw new InvalidVenueBookingException("Review comment must be at most 2000 characters.");
        if ("action_required".equals(decision.outcome()) && note==null)
            throw new InvalidVenueBookingException("Explain what the coordinator needs to do.");
        var checks=impact.assessBooking(event,booking);
        if ("suitable".equals(decision.outcome()) && checks.stream().anyMatch(c -> "attention".equals(c.status())))
            throw new VenueBookingStateException("Current venue checks require attention. Refresh the checks and record coordinator action required.");
        review.setStatus(decision.outcome()); review.setReviewNote(note); review.setReviewedBy(actor);
        review.setReviewerName(users.findById(actor).orElseThrow().getUsername()); review.setReviewedAt(OffsetDateTime.now());
        review.setReviewedChecks(checks.stream().map(c -> Map.of("key",c.key(),"area",c.area(),"status",c.status(),"title",c.title(),"detail",c.detail())).toList());
        reviews.saveAndFlush(review);
        String message="Venue review for " + review.getVenueAddress() + " (booking " + bookingId + "): "
                + ("suitable".equals(review.getStatus()) ? "remains suitable" : "coordinator action required")
                + (note == null ? "." : ". " + note);
        requests.findByEventId(eventId).ifPresent(origin -> activity.record(new ActivityService.Entry(origin.getRequestId(),eventId,
                ActivityType.venue_requirements_reviewed,actor,message.length()>2000 ? message.substring(0,1997)+"..." : message,null,null,null)));
        notifications.createVenueRequirementReviewedNotification(event.getCoordinatorId(),review,message);
        // No booking, event, reservation, hold or equipment write occurs here.
        return view(review,booking,event,true);
    }
    private View view(VenueRequirementReview review, VenueBookingRecord booking, Event event, boolean staff) {
        var checks = impact.assessBooking(event,booking);
        return new View(review,ChangeFields.changes(review.getPreviousRequirements(),review.getNewRequirements()),
                checks,booking.getStartDatetime()==null ? event.getStartDatetime() : booking.getStartDatetime(),
                booking.getEndDatetime()==null ? event.getEndDatetime() : booking.getEndDatetime(),booking.getSetupDurationMinutes(),
                booking.getTurnaroundDurationMinutes(),staff && "pending".equals(review.getStatus()) && booking.getStatus()!=VenueBookingStatus.attention && active(booking,event),impact.bookingSummary(event,booking,checks),booking.getStatus().name(),
                jdbc==null || Boolean.FALSE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM venue_booking_assessments WHERE booking_id=? AND created_at>?)
                    OR EXISTS(SELECT 1 FROM venue_requirement_reviews WHERE booking_id=? AND approved_at>?)
                    """,Boolean.class,booking.getBookingId(),review.getReviewedAt()==null ? review.getApprovedAt() : review.getReviewedAt(),booking.getBookingId(),review.getApprovedAt())));
    }
    private static boolean active(VenueBookingRecord b, Event e) {
        return (b.getStatus()==VenueBookingStatus.pending || b.getStatus()==VenueBookingStatus.approved || b.getStatus()==VenueBookingStatus.attention)
                && e.getStatus()!=EventStatus.cancelled && e.getStatus()!=EventStatus.completed
                && e.getEndDatetime().isAfter(OffsetDateTime.now());
    }
    private VenueBookingRecord booking(UUID id) { return bookings.findById(id).orElseThrow(() -> new VenueBookingNotFoundException(id)); }
    private Event event(UUID id) { return events.findById(id).orElseThrow(() -> new VenueBookingNotFoundException(id)); }
}
