package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;

/**
 * Published when Venue Staff record an unavailable period that affects
 * existing bookings (VS01/VS-C1). Listeners persist history and durable work inside the same
 * transaction; EC08 replanning runs after commit with retries. An event rather
 * than a direct call, so venue availability does not depend on the event
 * lifecycle that already depends on it.
 */
public record VenueBecameUnavailable(List<UUID> eventIds, UUID actorId, String reason) {
    public VenueBecameUnavailable {
        eventIds=List.copyOf(eventIds);
        // Detailed reasons remain on their source record; activity messages have a 2000-character limit.
        if (reason!=null && reason.length()>2000) reason=reason.substring(0,2000);
    }
}
