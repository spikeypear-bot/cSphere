package com.example.connect_sphere.venuebooking.hold;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.EventChange;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Settings;
import com.example.connect_sphere.venueavailability.EffectivePeriod;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.service.*;
import com.example.connect_sphere.notification.service.NotificationService;

@Service
@Transactional(readOnly=true)
public class VenueHoldService {
    private final VenueHoldRepository holds;
    private final VenueBookingRecordRepository bookings;
    private final EventRepository events;
    private final VenueRepository venues;
    private final AvailabilityService availability;
    private final ApplicationEventPublisher publisher;
    private final NotificationService notifications;
    public VenueHoldService(VenueHoldRepository holds, VenueBookingRecordRepository bookings, EventRepository events,
            VenueRepository venues, AvailabilityService availability, ApplicationEventPublisher publisher, NotificationService notifications) {
        this.holds=holds; this.bookings=bookings; this.events=events; this.venues=venues;
        this.availability=availability; this.publisher=publisher; this.notifications=notifications;
    }
    public List<VenueHold> history(UUID bookingId) {
        if (!bookings.existsById(bookingId)) throw new VenueBookingNotFoundException(bookingId);
        return holds.history(bookingId);
    }
    private VenueBookingRecord lock(UUID bookingId) {
        UUID eventId=bookings.findEventId(bookingId).orElseThrow(()->new VenueBookingNotFoundException(bookingId));
        events.findForUpdate(eventId).orElseThrow(()->new VenueBookingNotFoundException(bookingId));
        var booking=bookings.findById(bookingId).orElseThrow(()->new VenueBookingNotFoundException(bookingId));
        venues.findForUpdate(booking.getVenueId()).orElseThrow();
        return booking;
    }
    @Transactional
    public VenueHold create(UUID bookingId, OffsetDateTime expiry, UUID actor) {
        var b=lock(bookingId);
        var event=events.findById(b.getEventId()).orElseThrow();
        if (b.getStatus()!=VenueBookingStatus.pending || !event.getStatus().isPlanningEditable())
            throw new VenueBookingStateException("Holds require a pending booking for an event in Planning.");
        if (expiry==null || !expiry.isAfter(holds.now())) throw new InvalidVenueBookingException("Hold expiry must be later than the current server time.");
        settleOverdue(bookingId);
        if (holds.hasActive(bookingId)) throw new VenueBookingStateException("This booking already has an active tentative hold.");
        var start=b.getStartDatetime()==null?event.getStartDatetime():b.getStartDatetime();
        var end=b.getEndDatetime()==null?event.getEndDatetime():b.getEndDatetime();
        var durations=new Settings(b.getSetupDurationMinutes(),b.getTurnaroundDurationMinutes());
        var period=EffectivePeriod.of(start,end,durations);
        var conflicts=availability.conflictsForBooking(b.getVenueId(),bookingId,start,end,durations);
        var periods=availability.unavailablePeriods(b.getVenueId(),start,end,durations);
        if (!conflicts.isEmpty() || !periods.isEmpty()) throw new VenueBookingConflictException(
            "The occupied period conflicts with an approved booking, active tentative hold or unavailable period.",
            conflicts.stream().map(com.example.connect_sphere.venuebooking.dto.VenueBookingConflictDto::from).toList(),
            periods.stream().map(p -> new com.example.connect_sphere.venuebooking.dto.VenueUnavailabilityConflictDto(
                p.unavailabilityId(),p.reason(),p.startDateTime(),p.endDateTime())).toList());
        if (availability.hasOperationalIssue(b.getVenueId(),start,end,durations))
            throw new VenueBookingStateException("The venue is unavailable during the occupied period.");
        if (availability.outsideOperatingHours(b.getVenueId(),start,end,durations))
            throw new VenueBookingStateException("The complete occupied period must fit within venue opening hours.");
        if (!expiry.isAfter(holds.now())) throw new InvalidVenueBookingException("Hold expiry has already passed. Choose a later expiry.");
        UUID id=UUID.randomUUID();
        holds.insert(id,bookingId,period.start(),period.end(),expiry,actor);
        announce(b,ActivityType.venue_hold_created,actor,"Tentative hold created until "+expiry+". Occupied: "+period.start()+" to "+period.end()+".");
        return holds.history(bookingId).stream().filter(h->h.holdId().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public VenueHold release(UUID holdId, UUID actor) {
        UUID bookingId=holds.bookingId(holdId);
        var b=lock(bookingId);
        holds.requireActive(holdId,bookingId);
        holds.transition(holdId,"released",holds.now());
        announce(b,ActivityType.venue_hold_released,actor,"Tentative hold manually released.");
        return holds.history(bookingId).stream().filter(h->h.holdId().equals(holdId)).findFirst().orElseThrow();
    }
    /** Also used by rejection/cancellation; elapsed attempts are expired, never relabelled released. */
    @Transactional
    public void endForBooking(UUID bookingId, UUID actor) {
        var b=lock(bookingId);
        settleOverdue(bookingId);
        for (var hold:holds.history(bookingId)) if (hold.status().equals("active")) {
            holds.transition(hold.holdId(),"released",holds.now());
            announce(b,ActivityType.venue_hold_released,actor,"Tentative hold released with the booking decision.");
        }
    }
    private void settleOverdue(UUID bookingId) {
        for (var hold:holds.history(bookingId)) if (hold.status().equals("expired") && hold.endedAt()==null) expire(hold.holdId());
    }
    @Transactional
    public void expire(UUID holdId) {
        UUID bookingId=holds.bookingId(holdId);
        var b=lock(bookingId);
        var hold=holds.history(bookingId).stream().filter(h->h.holdId().equals(holdId)).findFirst().orElseThrow();
        if (hold.endedAt()!=null || hold.expiresAt().isAfter(holds.now())) return;
        holds.transition(holdId,"expired",hold.expiresAt());
        var event=events.findById(b.getEventId()).orElseThrow();
        notifications.createHoldExpiredNotification(event.getCoordinatorId(),b.getEventId(),bookingId,event.getEventName(),hold.expiresAt());
        announce(b,ActivityType.venue_hold_expired,hold.createdBy(),"System: tentative hold automatically expired at "+hold.expiresAt()+".");
    }
    private void announce(VenueBookingRecord b, ActivityType type, UUID actor, String text) {
        publisher.publishEvent(new EventChange(b.getEventId(),type,actor,text));
    }
}
