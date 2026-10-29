package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;

/**
 * EO-C1: something happened to one event's venue bookings that its organiser
 * may need to tell participants about (a venue became unavailable, an
 * operational issue, a cancelled booking, changed times, an approved
 * replacement). Published by the venue side in the same transaction as the
 * change; OrganiserVenueNotices decides whether the organiser is told. An
 * event rather than a call, so venue code does not depend on the event module
 * (as VenueBecameUnavailable).
 *
 * @param bookingIds the event's affected bookings only (EO-C1: only the
 *                   affected venue is named)
 * @param what       what happened, as the organiser should read it
 * @param reason     the reason given, if any (never internal staff notes)
 */
public record VenueArrangementChanged(UUID eventId, List<UUID> bookingIds, String what, String reason) {
    public VenueArrangementChanged {
        bookingIds = List.copyOf(bookingIds);
    }
}
