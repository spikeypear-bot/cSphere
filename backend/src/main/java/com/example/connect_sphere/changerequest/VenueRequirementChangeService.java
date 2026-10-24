package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/** VS17: joins the locked approval transaction, like the EO/EC audit trail.
 * A failed review or notification insert rolls back the entire approval.
 * Existing staff access is role-wide: there is no staff/venue ownership table. */
@Service
public class VenueRequirementChangeService {
    private static final Set<String> FIELDS = Set.of("startDatetime", "endDatetime", "expectedAttendance",
            "venueRequirements", "accessibilityNeeds", "requiredFacilities");
    private final VenueRequirementReviewRepository reviews;
    private final VenueBookingRecordRepository bookings;
    private final VenueRepository venues;
    private final UserRepository users;
    private final NotificationService notifications;

    public VenueRequirementChangeService(VenueRequirementReviewRepository reviews,
            VenueBookingRecordRepository bookings, VenueRepository venues, UserRepository users,
            NotificationService notifications) {
        this.reviews = reviews;
        this.bookings = bookings;
        this.venues = venues;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(ChangeRequest change, Event event, OffsetDateTime oldStart, OffsetDateTime oldEnd) {
        if (event == null || change.getStatus() != ChangeRequestStatus.approved) return;
        var changed = ChangeFields.changes(change.getOriginalValues(), change.getProposedValues()).stream()
                .filter(c -> FIELDS.contains(c.field())).toList();
        if (changed.isEmpty()) return;
        boolean timesChanged = changed.stream().anyMatch(c -> c.field().equals("startDatetime")
                || c.field().equals("endDatetime"));
        var coordinator = event.getCoordinatorId() == null ? null
                : users.findById(event.getCoordinatorId()).orElse(null);
        var recipients = users.findByRole(UserRole.vs).stream().map(u -> u.getUserId()).distinct().toList();
        for (var booking : bookings.findForEvent(event.getEventId())) {
            // V26 legacy rows inherit event times. Materialise the same reserved window
            // before a date change can alter that fallback, including historical bookings.
            if (timesChanged && (booking.getStartDatetime() == null || booking.getEndDatetime() == null)) {
                if (booking.getStartDatetime() == null) booking.setStartDatetime(oldStart);
                if (booking.getEndDatetime() == null) booking.setEndDatetime(oldEnd);
                bookings.save(booking);
            }
            if (booking.getStatus() != VenueBookingStatus.pending && booking.getStatus() != VenueBookingStatus.approved)
                continue;
            if (reviews.existsByChangeRequestIdAndBookingId(change.getChangeRequestId(), booking.getBookingId())) continue;
            var venue = venues.findById(booking.getVenueId()).orElseThrow();
            var review = new VenueRequirementReview();
            review.setReviewId(UUID.randomUUID());
            review.setChangeRequestId(change.getChangeRequestId());
            review.setBookingId(booking.getBookingId());
            review.setEventId(event.getEventId());
            review.setVenueId(booking.getVenueId());
            review.setCoordinatorId(event.getCoordinatorId());
            review.setCoordinatorName(coordinator == null ? null : coordinator.getUsername());
            review.setCoordinatorEmail(coordinator == null ? null : coordinator.getEmail());
            review.setEventName(event.getEventName());
            review.setVenueAddress(venue.getVenueAddress());
            review.setApprovedAt(change.getDecidedAt());
            review.setApprovedBy(change.getDecidedBy());
            review.setReservedStart(booking.getStartDatetime() == null ? oldStart : booking.getStartDatetime());
            review.setReservedEnd(booking.getEndDatetime() == null ? oldEnd : booking.getEndDatetime());
            review.setSetupDurationMinutes(booking.getSetupDurationMinutes());
            review.setTurnaroundDurationMinutes(booking.getTurnaroundDurationMinutes());
            review.setPreviousRequirements(snapshot(change.getOriginalValues()));
            review.setNewRequirements(snapshot(change.getProposedValues()));
            reviews.saveAndFlush(review);
            notifications.createVenueRequirementChangedNotifications(recipients, review, ChangeFields.describe(changed));
        }
    }

    private static Map<String, Object> snapshot(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        FIELDS.stream().sorted().forEach(key -> {
            Object value = values.get(key);
            copy.put(key, value instanceof List<?> list ? List.copyOf(list) : value);
        });
        return copy;
    }
}
