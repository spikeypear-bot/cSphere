package com.example.connect_sphere.equipmentreview;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.equipment.EquipmentReservationResponse;
import com.example.connect_sphere.equipment.EquipmentReservationService;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Detail;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.OutcomeRequest;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Reservation;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Summary;
import com.example.connect_sphere.equipmentreview.EquipmentChangeReviewDto.Values;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.event.service.EventNotFoundException;
import com.example.connect_sphere.eventrequest.service.NotAssignedCoordinatorException;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * TS11 AC10-AC14: Technical Support read and record their review of an
 * approved change's equipment impact; the assigned coordinator (and the Lead,
 * read-only) see the outcome. Recording an outcome changes nothing but the
 * review: no reservation, equipment request or event status (AC13).
 */
@Service
public class EquipmentChangeReviewService {

    static final int MAX_NOTE = 2000;

    private final EquipmentChangeReviewRepository reviews;
    private final EventRepository events;
    private final EquipmentReservationService reservations;
    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public EquipmentChangeReviewService(EquipmentChangeReviewRepository reviews, EventRepository events,
            EquipmentReservationService reservations, UserRepository users) {
        this(reviews, events, reservations, users, Clock.systemUTC());
    }

    EquipmentChangeReviewService(EquipmentChangeReviewRepository reviews, EventRepository events,
            EquipmentReservationService reservations, UserRepository users, Clock clock) {
        this.reviews = reviews;
        this.events = events;
        this.reservations = reservations;
        this.users = users;
        this.clock = clock;
    }

    /** Technical Support's queue: reviews still needed first, then newest first. */
    @Transactional(readOnly = true)
    public List<Summary> queue() {
        return reviews.findAllByOrderByChangedAtDesc().stream()
                .sorted(Comparator.comparing((EquipmentChangeReview r) -> !r.needsReview()))
                .map(r -> summary(r, eventName(r.getEventId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID reviewId) {
        return detail(find(reviewId));
    }

    /** AC11-AC14. A note is required for "needs coordinator action"; a review is recorded once. */
    @Transactional
    public Detail recordOutcome(UUID reviewerId, UUID reviewId, OutcomeRequest input) {
        EquipmentChangeReview review = reviews.findForUpdate(reviewId)
                .orElseThrow(() -> notFound(reviewId));
        ReviewOutcome outcome = outcomeOf(input == null ? null : input.outcome());
        String note = input == null || input.note() == null ? null : input.note().strip();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (outcome == ReviewOutcome.needs_coordinator_action && note == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what the coordinator needs to do.");
        }
        if (note != null && note.length() > MAX_NOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The note can be at most " + MAX_NOTE + " characters.");
        }
        if (!review.needsReview()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This change has already been reviewed.");
        }
        review.setOutcome(outcome);
        review.setOutcomeNote(note);
        review.setReviewedBy(reviewerId);
        review.setReviewedAt(OffsetDateTime.now(clock));
        return detail(reviews.save(review));
    }

    /** AC12: the assigned coordinator, or the Lead (read-only), sees the outcomes for an event. */
    @Transactional(readOnly = true)
    public List<Summary> forEvent(UUID callerId, boolean lead, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (!lead && (callerId == null || !callerId.equals(event.getCoordinatorId()))) {
            throw new NotAssignedCoordinatorException(eventId);
        }
        return reviews.findByEventIdOrderByChangedAtDesc(eventId).stream()
                .map(r -> summary(r, event.getEventName()))
                .toList();
    }

    private Detail detail(EquipmentChangeReview review) {
        Event event = events.findById(review.getEventId())
                .orElseThrow(() -> new EventNotFoundException(review.getEventId()));
        Set<String> affected = review.getAffectedReservations().stream()
                .map(r -> String.valueOf(r.get("logId"))).collect(Collectors.toSet());
        boolean open = review.needsReview();
        List<Reservation> current = reservations.reservationsForEvent(event.getEventId()).stream()
                .map(r -> reservation(r, open && affected.contains(r.logId().toString())))
                .toList();
        List<Reservation> atChange = review.getAffectedReservations().stream()
                .map(r -> new Reservation(UUID.fromString(r.get("logId").toString()),
                        (String) r.get("equipmentName"), ((Number) r.get("quantity")).intValue(),
                        (String) r.get("serialNumber"), Instant.parse(r.get("loanedFrom").toString()),
                        Instant.parse(r.get("loanedUntil").toString()), open))
                .toList();
        return new Detail(review.getReviewId(), event.getEventId(), event.getEventName(),
                event.getStartDatetime(), event.getEndDatetime(), nameOf(review.getCoordinatorId()),
                review.getChangedAt(), values(review.getPreviousValues()), values(review.getUpdatedValues()),
                review.isRequirementsChanged(), atChange, current, open,
                review.getOutcome() == null ? null : review.getOutcome().name(), review.getOutcomeNote(),
                nameOf(review.getReviewedBy()), review.getReviewedAt());
    }

    private Summary summary(EquipmentChangeReview r, String eventName) {
        return new Summary(r.getReviewId(), r.getEventId(), eventName, r.getChangedAt(), r.needsReview(),
                r.getAffectedReservations().size(), r.isRequirementsChanged(),
                r.getOutcome() == null ? null : r.getOutcome().name(), r.getOutcomeNote(),
                nameOf(r.getReviewedBy()), r.getReviewedAt());
    }

    private static Reservation reservation(EquipmentReservationResponse r, boolean flagged) {
        return new Reservation(r.logId(), r.equipmentName(), r.quantity(), r.serialNumber(), r.loanedFrom(),
                r.loanedUntil(), flagged);
    }

    private static Values values(Map<String, Object> v) {
        return new Values(time(v.get(EquipmentChangeImpact.START)), time(v.get(EquipmentChangeImpact.END)),
                (String) v.get(EquipmentChangeImpact.REQUIREMENTS));
    }

    private static OffsetDateTime time(Object value) {
        return value == null ? null : OffsetDateTime.parse(value.toString());
    }

    private static ReviewOutcome outcomeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a review outcome.");
        }
        try {
            return ReviewOutcome.valueOf(raw.strip());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The outcome must be still_suitable or needs_coordinator_action.");
        }
    }

    private EquipmentChangeReview find(UUID reviewId) {
        return reviews.findById(reviewId).orElseThrow(() -> notFound(reviewId));
    }

    private static ResponseStatusException notFound(UUID reviewId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipment change review " + reviewId + " not found");
    }

    private String eventName(UUID eventId) {
        return events.findById(eventId).map(Event::getEventName).orElse("Unknown event");
    }

    private String nameOf(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::getUsername).orElse(null);
    }
}
