package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;

/**
 * Published when Venue Staff record an unavailable period that affects
 * existing bookings (VS01/VS-C1). Listeners react inside the same
 * transaction, e.g. EC08 returns affected events to Planning. An event rather
 * than a direct call, so venue availability does not depend on the event
 * lifecycle that already depends on it.
 */
public record VenueBecameUnavailable(List<UUID> eventIds, UUID actorId, String reason) {
}
