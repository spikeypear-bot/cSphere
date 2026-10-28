package com.example.connect_sphere.event.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.VenueArrangementChanged;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EO-C1: an organiser whose event has been Confirmed at least once is told
 * when its venue arrangements change, so they can tell participants. One
 * notice per change, naming only the affected venues, never another event or
 * a staff note. Written in the change's own transaction through the
 * notification outbox: it reaches the organiser as soon as the change is
 * saved, and never for a change that was not saved. VS17 reassessments
 * already notify organisers (BookingAssessmentService) and are not repeated.
 */
@Component
public class OrganiserVenueNotices {

    private static final DateTimeFormatter SINGAPORE = DateTimeFormatter
            .ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Singapore"));

    private final EventRepository events;
    private final VenueBookingRecordRepository bookings;
    private final VenueRepository venues;
    private final UserRepository users;
    private final NotificationService notifications;

    public OrganiserVenueNotices(EventRepository events, VenueBookingRecordRepository bookings, VenueRepository venues,
            UserRepository users, NotificationService notifications) {
        this.events = events;
        this.bookings = bookings;
        this.venues = venues;
        this.users = users;
        this.notifications = notifications;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(VenueArrangementChanged change) {
        Event event = events.findById(change.eventId()).orElse(null);
        if (!applies(event) || change.bookingIds().isEmpty()) {
            return;
        }
        List<String> affected = change.bookingIds().stream().map(this::venueOf).filter(Objects::nonNull)
                .distinct().sorted().toList();
        String message = change.what() + (affected.isEmpty() ? "" : " Affected venue" + (affected.size() == 1 ? ": " : "s: ")
                + String.join("; ", affected) + ".")
                + (change.reason() == null || change.reason().isBlank() ? "" : " Reason: " + change.reason().strip() + ".")
                + " Recorded " + SINGAPORE.format(OffsetDateTime.now()) + " (Singapore time)."
                + " Your event's arrangements are being re-confirmed; until then, treat " + (affected.size() == 1
                        ? "this venue" : "these venues") + " as not confirmed."
                + (othersStillConfirmed(event, change.bookingIds()) ? " Its other venues stay as confirmed." : "");
        notifications.createOrganiserVenueChangeNotifications(organisers(event), event.getEventId(),
                change.bookingIds().get(0), event.getEventName(), message);
    }

    /** The follow-up once an event that had been confirmed is confirmed again,
     * naming the venues it now has, with their addresses. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconfirmed(Event event) {
        if (!applies(event)) {
            return;
        }
        List<VenueBookingRecord> approved = bookings.findForEvent(event.getEventId()).stream()
                .filter(b -> b.getStatus() == VenueBookingStatus.approved).toList();
        if (approved.isEmpty()) {
            return;
        }
        List<String> names = approved.stream().map(VenueBookingRecord::getBookingId).map(this::venueOf)
                .filter(Objects::nonNull).distinct().sorted().toList();
        notifications.createOrganiserVenueChangeNotifications(organisers(event), event.getEventId(),
                approved.get(0).getBookingId(), event.getEventName(),
                "Your event is confirmed again with " + (names.size() == 1 ? "this venue: " : "these venues: ")
                        + String.join("; ", names) + ". You can share these arrangements with participants.");
    }

    /** EC-C2: an event with several venues keeps the ones not affected. */
    private boolean othersStillConfirmed(Event event, List<UUID> affected) {
        return bookings.findForEvent(event.getEventId()).stream()
                .anyMatch(b -> b.getStatus() == VenueBookingStatus.approved && !affected.contains(b.getBookingId()));
    }

    /** Confirmed at least once, and still to happen. */
    private static boolean applies(Event event) {
        return event != null && event.getFirstConfirmedAt() != null
                && event.getStatus() != EventStatus.cancelled && event.getStatus() != EventStatus.completed
                && event.getEndDatetime() != null && event.getEndDatetime().isAfter(OffsetDateTime.now());
    }

    private List<UUID> organisers(Event event) {
        // Organisers are scoped by organisation (AU04), as the VS17 notices are.
        return users.findByRoleAndOrganisation(UserRole.eo, event.getOrganisation()).stream().map(User::getUserId).toList();
    }

    private String venueOf(UUID bookingId) {
        return bookings.findById(bookingId).flatMap(b -> venues.findById(b.getVenueId()))
                .map(Venue::getVenueAddress).orElse(null);
    }
}
