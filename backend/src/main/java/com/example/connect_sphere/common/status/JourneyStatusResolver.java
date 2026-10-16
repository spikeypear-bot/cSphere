package com.example.connect_sphere.common.status;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;

/**
 * EC-NEW2: the single place where a stored status becomes a
 * {@link JourneyStatus}. Every DTO that shows a status gets it from here.
 *
 * <p>Completed is worked out on read (a Confirmed event whose end has passed)
 * rather than by a scheduled job, so it can never lag behind the clock, and
 * no screen can disagree with another about it.
 */
@Component
public class JourneyStatusResolver {

    private final EventRepository events;
    private final Clock clock;

    @Autowired
    public JourneyStatusResolver(EventRepository events) {
        this(events, Clock.systemUTC());
    }

    public JourneyStatusResolver(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** A request's status; once approved, it is its event's status. */
    @Named("journeyStatus")
    public StatusView forRequest(EventRequest request) {
        if (request == null || request.getStatus() == null) {
            return StatusView.of(JourneyStatus.DRAFT, false);
        }
        return switch (request.getStatus()) {
            case draft -> StatusView.of(JourneyStatus.DRAFT, false);
            case pending -> StatusView.of(request.getCoordinatorId() == null
                    ? JourneyStatus.SUBMITTED : JourneyStatus.UNDER_REVIEW, false);
            case clarification_required -> StatusView.of(JourneyStatus.CLARIFICATION_REQUIRED, false);
            case rejected -> StatusView.of(JourneyStatus.REJECTED, false);
            case cancelled -> StatusView.of(JourneyStatus.CANCELLED, false);
            case approved -> request.getEventId() == null
                    ? StatusView.of(JourneyStatus.PLANNING, false)
                    : events.findById(request.getEventId()).map(this::forEvent)
                            .orElse(StatusView.of(JourneyStatus.PLANNING, false));
        };
    }

    public StatusView forEvent(Event event) {
        JourneyStatus status = journeyOf(event);
        boolean passed = event.getEndDatetime() != null && event.getEndDatetime().isBefore(now());
        // A Confirmed event whose end has passed is already Completed (final),
        // so only unconfirmed, unfinished events can be flagged.
        boolean datePassed = passed && !status.isFinal();
        return StatusView.of(status, datePassed);
    }

    public JourneyStatus journeyOf(Event event) {
        return switch (event.getStatus()) {
            case pending -> JourneyStatus.PLANNING;
            case awaiting_safety_review -> JourneyStatus.AWAITING_SAFETY_REVIEW;
            case safety_changes_requested -> JourneyStatus.SAFETY_CHANGES_REQUESTED;
            case safety_approved -> JourneyStatus.SAFETY_APPROVED;
            case confirmed -> event.getEndDatetime() != null && event.getEndDatetime().isBefore(now())
                    ? JourneyStatus.COMPLETED : JourneyStatus.CONFIRMED;
            case completed -> JourneyStatus.COMPLETED;
            case cancelled -> JourneyStatus.CANCELLED;
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
