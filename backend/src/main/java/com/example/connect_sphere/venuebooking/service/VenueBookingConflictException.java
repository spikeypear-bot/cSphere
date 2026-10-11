package com.example.connect_sphere.venuebooking.service;

import java.util.List;
import com.example.connect_sphere.venuebooking.dto.VenueBookingConflictDto;
import com.example.connect_sphere.venuebooking.dto.VenueUnavailabilityConflictDto;

public class VenueBookingConflictException extends VenueBookingStateException {
    private final List<VenueBookingConflictDto> conflicts;
    private final List<VenueUnavailabilityConflictDto> unavailablePeriods;

    public VenueBookingConflictException(List<VenueBookingConflictDto> conflicts) {
        this("This booking overlaps an existing approved booking for the selected venue.", conflicts);
    }

    public VenueBookingConflictException(String message, List<VenueBookingConflictDto> conflicts) {
        this(message, conflicts, List.of());
    }

    public VenueBookingConflictException(String message, List<VenueBookingConflictDto> conflicts,
            List<VenueUnavailabilityConflictDto> unavailablePeriods) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
        this.unavailablePeriods = List.copyOf(unavailablePeriods);
    }

    public List<VenueBookingConflictDto> getConflicts() { return conflicts; }
    public List<VenueUnavailabilityConflictDto> getUnavailablePeriods() { return unavailablePeriods; }
}
