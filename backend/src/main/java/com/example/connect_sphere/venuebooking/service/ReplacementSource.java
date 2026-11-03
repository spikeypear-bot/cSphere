package com.example.connect_sphere.venuebooking.service;

import com.example.connect_sphere.venueavailability.service.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;

/** EC10: which bookings a coordinator may ask to replace, the same rule at
 * submission and at Venue Staff's approval. A rejected, cancelled, approved or
 * flagged booking may be replaced; a pending one only once a recorded
 * unavailability or operational issue now overlaps it; a superseded one never. */
final class ReplacementSource {

    private ReplacementSource() {}

    static boolean eligible(VenueBookingRecord booking, AvailabilityService availability) {
        return switch (booking.getStatus()) {
            case rejected, cancelled, approved, attention -> true;
            case pending -> availability.affected(booking.getBookingId());
            case changed -> false;
        };
    }
}
