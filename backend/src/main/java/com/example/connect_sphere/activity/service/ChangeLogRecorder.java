package com.example.connect_sphere.activity.service;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;
import com.example.connect_sphere.venueavailability.VenueBecameUnavailable;

/**
 * EC-NEW5: turns {@link EventChange}s (and venues becoming unavailable) into
 * change-log entries on the event's originating request. Venue and equipment
 * services only announce what happened; this is the one place that knows how
 * the log is kept.
 *
 * <p>Listeners are synchronous: the entry is written in the publisher's
 * transaction ({@code ActivityService.record} is MANDATORY), so it commits or
 * rolls back with the change itself.
 */
@Component
public class ChangeLogRecorder {

    private final ActivityService activity;
    private final EventRequestRepository eventRequests;

    public ChangeLogRecorder(ActivityService activity, EventRequestRepository eventRequests) {
        this.activity = activity;
        this.eventRequests = eventRequests;
    }

    @EventListener
    public void on(EventChange change) {
        UUID actor = change.actorUserId() != null ? change.actorUserId() : currentUser();
        if (actor == null || change.eventId() == null) {
            return;
        }
        // An event created outside the request flow has no log to extend.
        eventRequests.findByEventId(change.eventId()).ifPresent(origin -> activity.record(new ActivityService.Entry(
                origin.getRequestId(), change.eventId(), change.type(), actor, change.message(), null, null, null)));
    }

    /** VS01: one entry on each affected event, written with the unavailability. */
    @EventListener
    public void on(VenueBecameUnavailable unavailable) {
        for (UUID eventId : unavailable.eventIds()) {
            on(new EventChange(eventId, ActivityType.venue_unavailable, unavailable.actorId(), unavailable.reason()));
        }
    }

    private static UUID currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException notAUserId) {
                return null;
            }
        }
        return null;
    }
}
